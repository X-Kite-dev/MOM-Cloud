package com.kitex.mom.qms.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.qms.agent.dto.QmsRootCauseImportDTO.Candidate;
import com.kitex.mom.qms.agent.dto.QmsRootCauseImportDTO.Command;
import com.kitex.mom.qms.agent.dto.QmsRootCauseImportDTO.Receipt;
import com.kitex.mom.qms.agent.dto.QmsRootCauseImportDTO.Source;
import com.kitex.mom.qms.dto.req.QmsCapaCauseAddReq;
import com.kitex.mom.qms.entity.QmsCapaCauseEntity;
import com.kitex.mom.qms.entity.QmsCapaEntity;
import com.kitex.mom.qms.mapper.QmsCapaCauseMapper;
import com.kitex.mom.qms.mapper.QmsCapaMapper;
import com.kitex.mom.qms.service.QmsCapaService;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 质量假设的人工引用编排：先保存原命令，反查真实保存来源，再由原QMS事务提出待验证根因。
 * 准备、取消和最终受理共用原号锁；远端读取不占本地事务，也不构成跨域原子事务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QmsRootCauseImportService {

	private static final List<String> SOURCE_PERMISSIONS = List.of("agent:run:view", "agent:industry:run",
			"mesTask:show", "procedureTask:show", "productTask:show");

	private final QmsAgentFactoryAccessService factoryAccess;
	private final PermissionService permission;
	private final QmsRootCauseRequestStore store;
	private final QmsRootCauseTargetQuery targets;
	private final QmsCapaEvidenceQueryService evidenceQuery;
	private final QmsCapaMapper capas;
	private final QmsCapaCauseMapper causes;
	private final ObjectProvider<QmsCapaService> capaService;
	private final ObjectProvider<RemoteAiRootCauseProof> ai;
	private final ObjectProvider<RemoteMdmFactoryAccessService> factories;
	private final ObjectMapper json;
	private final Validator validator;
	private final PlatformTransactionManager manager;

	/** 只读保存来源反查后真分页；不把候选或模型文字当成最后写入授权。 */
	public Page<Candidate> candidates(Source source, long current, long size, String capaNo) {
		// 本候选专供人工提出准备，先核实际工厂写资格，避免只读人员被引导登记无法取消的命令。
		access(true);
		store.requireInstalled();
		validate(source);
		require(current > 0 && current <= 100000 && size > 0 && size <= 20, "候选每页1至20条，页码须为正数");
		require(capaNo == null || capaNo.length() <= 100, "CAPA编号筛选最长100字符");
		if (capaNo != null && !capaNo.isBlank()) {
			validText(capaNo);
		}
		Map<String, Object> proof = proof(source);
		return transaction(true).execute(status -> {
			Page<Candidate> result = new Page<>(current, size, targets.count(proof, capaNo));
			List<Candidate> records = new ArrayList<>();
			for (Long capaId : targets.page(proof, capaNo, current, size)) {
				var observed = evidenceQuery.query(capaId);
				String reason = !"ROOT_CAUSE".equals(observed.getStatus()) ? "当前不在根因分析阶段"
						: observed.getCurrentCycle() == null || observed.getCurrentCycle() < 1 ? "案件当前周期未核实"
						: observed.getMissingEvidence().stream().anyMatch(gap -> gap.contains("PERMISSION")) ? "原来源权限不完整" : "";
				records.add(new Candidate(capaId.toString(), observed.getCapaNo(), observed.getCurrentCycle(),
						observed.getStatus(), Objects.toString(observed.getCapa().get("title"), ""),
						observed.getSourceFingerprint(), reason.isEmpty(), reason));
			}
			return result.setRecords(records);
		});
	}

	/**
	 * 原命令准备是独立短事务，远端失败保持PREPARED；相同请求已终结时只回原结果。
	 * 新受理最终调用原addCause，不绕过真实证据、当前周期或独立验证。
	 */
	public Receipt importCause(Command command) {
		access(true);
		store.requireInstalled();
		validate(command);
		Map<String, Object> prepared = prepare(command, false);
		if (!"PREPARED".equals(prepared.get("status"))) {
			checkSavedPermissions(prepared);
			return receipt(prepared);
		}
		Map<String, Object> proof = proof(command.source());
		return transaction(false).execute(status -> {
			store.lock(command.requestId());
			Map<String, Object> request = store.find(command.requestId(), true);
			match(request, command);
			if (!"PREPARED".equals(request.get("status"))) {
				checkSavedPermissions(request);
				return receipt(request);
			}
			require(SecurityUtils.getUser().getUsername().equals(text(request, "actor_code")),
					"原人员账号快照已变化，请先取消原号再重新发起，不能改写原提出身份");
			QmsCapaEntity capa = capas.selectOne(new LambdaQueryWrapper<QmsCapaEntity>()
					.eq(QmsCapaEntity::getTenantId, TenantContextHolder.getTenantId())
					.eq(QmsCapaEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
					.eq(QmsCapaEntity::getId, id(command.capaId())).last("FOR UPDATE"));
			require(capa != null && "ROOT_CAUSE".equals(capa.getStatus())
					&& Objects.equals(capa.getCycleNo(), command.expectedCycleNo()), "原CAPA已变化或不在选定根因周期，请重新选择");
			targets.lockSource(proof, capa.getId());
			var observed = evidenceQuery.query(capa.getId());
			require(command.expectedCaseFingerprint().equals(observed.getSourceFingerprint())
					&& Objects.equals(command.expectedCycleNo(), observed.getCurrentCycle()), "案件事实或周期已变化，请重新读取原案件");
			require(observed.getMissingEvidence().stream().noneMatch(gap -> gap.contains("PERMISSION")), "当前岗位无法核对原质量来源");
			require(!store.accepted(command), "本案件周期已引用过该保存假设，请查看原根因，不得换号重复提出");
			// 原addCause的旧幂等仅比较请求号；新引用不能事后为同号人工根因补造AI来源。
			require(causes.selectCount(new LambdaQueryWrapper<QmsCapaCauseEntity>()
					.eq(QmsCapaCauseEntity::getTenantId, TenantContextHolder.getTenantId())
					.eq(QmsCapaCauseEntity::getRequestId, command.requestId())) == 0,
					"原请求号已用于其他根因，不能事后补造AI引用来源");
			QmsCapaCauseAddReq add = new QmsCapaCauseAddReq();
			add.setRequestId(command.requestId());
			add.setCapaId(capa.getId());
			add.setCauseType(proof.get("hypothesisKind").toString());
			add.setAnalysisMethod(command.analysisMethod());
			add.setCauseDescription(command.causeDescription());
			add.setEvidence(command.evidence());
			Long causeId = capaService.getObject().addCause(add);
			QmsCapaCauseEntity cause = cause(causeId, capa.getId());
			require(cause != null && Objects.equals(cause.getCycleNo(), command.expectedCycleNo())
					&& command.requestId().equals(cause.getRequestId()) && "PENDING".equals(cause.getVerificationStatus())
					&& Objects.equals(cause.getCauseType(), add.getCauseType())
					&& Objects.equals(cause.getAnalysisMethod(), command.analysisMethod())
					&& Objects.equals(cause.getCauseDescription(), command.causeDescription())
					&& Objects.equals(cause.getEvidence(), command.evidence())
					&& Objects.equals(cause.getProposedBy(), SecurityUtils.getUser().getUsername()),
					"原请求号已对应其他根因或人员，不能登记为本次AI引用");
			String proofJson = write(proof);
			store.accept(command.requestId(), proof, proofJson, hash(proofJson), write(proof.get("sourcePermissions")),
					causeId, cause.getCauseNo());
			return receipt(store.find(command.requestId(), false));
		});
	}

	/** 原人原组织只读核账；null不能被解释为尚未发送或永久未受理。 */
	public Receipt requestReceipt(String requestId) {
		access(false);
		store.requireInstalled();
		requestId(requestId);
		Map<String, Object> request = store.find(requestId, false);
		if (request == null) {
			return null;
		}
		require(actor().equals(text(request, "actor_user_id")), "原引用请求不属于当前人员");
		checkSavedPermissions(request);
		return receipt(request);
	}

	/** 原完整命令取消与受理共享锁；ACCEPTED只返回真实原根因，不撤销业务事实。 */
	public Receipt cancel(Command command) {
		access(true);
		store.requireInstalled();
		validate(command);
		return receipt(prepare(command, true));
	}

	/** 原CAPA精确根因的保存证明；MDM授权仍须可核验，读取不反向探活AI。 */
	public Map<String, Object> source(String causeId, String capaId) {
		access(false);
		store.requireInstalled();
		QmsCapaCauseEntity cause = cause(id(causeId), id(capaId));
		require(cause != null, "当前实际组织未找到该案件的根因");
		evidenceQuery.query(id(capaId));
		Map<String, Object> request = store.source(id(causeId), id(capaId));
		if (request == null) {
			return null;
		}
		checkSavedPermissions(request);
		return Map.of("receipt", receipt(request), "proof", readProof(request));
	}

	/**
	 * 原根因异人验证的窄补充闸门，仅AI接受来源使用稳定人员主键。
	 * 不读取冻结正文、不要求AI权限或AI网络；仅已有AI来源核MDM实际工厂写权限。
	 * 账号改名不能使同一提出人自证，普通无AI来源验证不新增RPC。
	 */
	public void requireIndependentVerifier(QmsCapaCauseEntity cause) {
		factoryAccess.requireRead(FactoryContextHolder.getFactoryCode());
		if (!store.exists()) {
			return;
		}
		Map<String, Object> request = store.source(cause.getId(), cause.getCapaId());
		if (request != null) {
			requireFactoryAccess(true);
			require(!actor().equals(text(request, "actor_user_id")), "AI引用根因的提出人与验证人必须为不同人员，账号改名不改变原身份");
		}
	}

	/** 只装饰已安装技术账的本地摘要；不改变人工根因或依赖远端AI。 */
	public void decorate(List<QmsCapaCauseEntity> rows) {
		if (rows.isEmpty()) {
			return;
		}
		try {
			decorateInstalled(rows);
		} catch (DataAccessException unavailable) {
			// 可选技术账未授权或正在安装时，仍交付原人工根因；新引用接口保持明确失败。
			log.warn("可选AI根因来源摘要当前不可读，原CAPA根因保持可用：{}", unavailable.getClass().getSimpleName());
		}
	}

	private void decorateInstalled(List<QmsCapaCauseEntity> rows) {
		if (!store.installed()) {
			return;
		}
		Map<Long, Map<String, Object>> byCause = new java.util.HashMap<>();
		for (Map<String, Object> request : store.sources(rows.stream().map(QmsCapaCauseEntity::getId).toList())) {
			byCause.put(((Number) request.get("cause_id")).longValue(), request);
		}
		for (QmsCapaCauseEntity row : rows) {
			Map<String, Object> request = byCause.get(row.getId());
			if (request != null && row.getCapaId().toString().equals(text(request, "capa_id"))) {
				row.setAiSourceRequestId(text(request, "request_id")).setAiSourceRunId(text(request, "run_id"))
						.setAiSourceArtifactId(text(request, "analysis_artifact_id")).setAiSourceHypothesisId(text(request, "hypothesis_id"));
			}
		}
	}

	/** 准备和取消均先锁原号，未命中取消也永久封存完整命令，拒绝迟到写入。 */
	private Map<String, Object> prepare(Command command, boolean cancel) {
		return transaction(false).execute(status -> {
			store.lock(command.requestId());
			Map<String, Object> request = store.find(command.requestId(), true);
			if (request == null) {
				QmsCapaEntity target = capas.selectOne(new LambdaQueryWrapper<QmsCapaEntity>()
						.eq(QmsCapaEntity::getId, id(command.capaId())).eq(QmsCapaEntity::getTenantId, TenantContextHolder.getTenantId())
						.eq(QmsCapaEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()));
				require(target != null && target.getCapaNo() != null, "当前实际组织未找到原CAPA，不能登记原号");
				store.insert(command, target.getCapaNo(), commandHash(command), write(command), cancel ? "CANCELLED" : "PREPARED");
			} else {
				match(request, command);
				checkSavedPermissions(request);
				if (cancel && "PREPARED".equals(request.get("status"))) {
					store.cancel(command.requestId());
				}
			}
			return store.find(command.requestId(), false);
		});
	}

	/** 固定AI合同与当前实际组织逐项核对，模型计划只保存在proof中。 */
	private Map<String, Object> proof(Source source) {
		var response = ai.getObject().proof(source);
		require(response != null && response.getCode() == 0 && response.getData() != null, "AI保存来源当前无法核验，请保留原号稍后核查");
		Map<String, Object> proof = response.getData();
		require(Integer.valueOf(1).equals(proof.get("contractVersion"))
				&& source.runId().equals(proof.get("runId")) && source.analysisArtifactId().equals(proof.get("analysisArtifactId"))
				&& source.analysisContentHash().equals(proof.get("analysisContentHash"))
				&& source.hypothesisId().equals(proof.get("hypothesisId"))
				&& TenantContextHolder.getTenantId().toString().equals(proof.get("tenantId"))
				&& FactoryContextHolder.getFactoryCode().equals(proof.get("factoryCode")), "AI保存来源的运行、组织或精确分析版不匹配");
		id(string(proof, "taskId"));
		id(string(proof, "evidenceArtifactId"));
		require(string(proof, "evidenceContentHash").matches("[a-f0-9]{64}")
				&& string(proof, "sourceContextHash").matches("[a-f0-9]{64}")
				&& Set.of("OCCURRENCE", "ESCAPE").contains(string(proof, "hypothesisKind")), "AI原取证摘要或假设类型不完整");
		for (String key : List.of("taskCode", "hypothesisStatement", "modelProvider", "modelCode")) {
			validText(string(proof, key));
		}
		require(proof.get("verification") instanceof Map<?, ?> && proof.get("supportingFacts") instanceof List<?>
				&& proof.get("contradictingFacts") instanceof List<?> && proof.get("missingEvidence") instanceof List<?>, "AI保存假设正文或验证计划不完整");
		checkPermissions(proof.get("sourcePermissions"));
		require(write(proof).length() <= 262144, "AI保存来源证明超过接收范围");
		return proof;
	}

	private void checkSavedPermissions(Map<String, Object> request) {
		if (request.get("source_proof_json") != null) {
			checkPermissions(readProof(request).get("sourcePermissions"));
		} else {
			for (String code : SOURCE_PERMISSIONS) {
				require(permission.hasPermission(code), "当前岗位缺少原质量来源权限：" + code);
			}
		}
	}

	private void checkPermissions(Object values) {
		require(values instanceof List<?>, "保存来源未包含原权限列表");
		List<?> codes = (List<?>) values;
		require(codes.size() >= SOURCE_PERMISSIONS.size() && codes.size() <= 200
				&& codes.containsAll(SOURCE_PERMISSIONS), "保存来源原权限不完整");
		for (Object value : codes) {
			require(value instanceof String && !((String) value).isBlank() && ((String) value).length() <= 200,
					"保存来源权限项不合法");
			require(permission.hasPermission((String) value), "当前岗位缺少原质量来源权限：" + value);
		}
	}

	private Map<String, Object> readProof(Map<String, Object> request) {
		String body = text(request, "source_proof_json");
		require(hash(body).equals(text(request, "proof_hash")), "原引用冻结证明摘要不一致，请人工核查");
		try {
			return json.readValue(body, new TypeReference<Map<String, Object>>() { });
		} catch (Exception invalid) {
			throw new CheckedException("原引用保存证明无法读取");
		}
	}

	private Receipt receipt(Map<String, Object> row) {
		QmsCapaCauseEntity cause = row.get("cause_id") == null ? null : cause(((Number) row.get("cause_id")).longValue(),
				((Number) row.get("capa_id")).longValue());
		require(!"ACCEPTED".equals(row.get("status")) || cause != null && text(row, "cause_no").equals(cause.getCauseNo())
				&& Objects.equals(cause.getCycleNo(), ((Number) row.get("cycle_no")).intValue()), "已接受引用缺少真实原根因，请核查账目完整性");
		return new Receipt(text(row, "request_id"), text(row, "status"), text(row, "capa_id"), text(row, "capa_no"),
				((Number) row.get("cycle_no")).intValue(), text(row, "case_fingerprint"), text(row, "run_id"), text(row, "analysis_artifact_id"),
				text(row, "analysis_content_hash"), text(row, "hypothesis_id"), text(row, "actor_user_id"),
				text(row, "analysis_method"), text(row, "cause_description"), text(row, "evidence"),
				cause == null ? null : cause.getId().toString(), cause == null ? null : cause.getCauseNo(),
				cause == null ? null : cause.getVerificationStatus());
	}

	private QmsCapaCauseEntity cause(Long causeId, Long capaId) {
		return causes.selectOne(new LambdaQueryWrapper<QmsCapaCauseEntity>().eq(QmsCapaCauseEntity::getId, causeId)
				.eq(QmsCapaCauseEntity::getCapaId, capaId).eq(QmsCapaCauseEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(QmsCapaCauseEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()));
	}

	private void access(boolean write) {
		requireFactoryAccess(write);
		require(permission.hasPermission("capa:show") && permission.hasPermission("bad:show")
				&& (!write || permission.hasPermission("capa:analyze")), "当前岗位缺少原NCR/CAPA查看或分析权限");
		require(SecurityUtils.getUser().getUsername() != null && !SecurityUtils.getUser().getUsername().isBlank(), "质量假设引用需要实际人员账号");
		for (String code : SOURCE_PERMISSIONS) {
			require(permission.hasPermission(code), "当前岗位缺少原质量来源权限：" + code);
		}
	}

	/**
	 * 选择工厂的请求头只表达意图；先核认证主体与当前租户一致，再由MDM核实名工厂绑定。
	 * 仅新显式入口和已有AI来源的验证使用；权威不可达或业务拒绝均明确失败，不冒充空业务。
	 */
	private void requireFactoryAccess(boolean write) {
		String factory = FactoryContextHolder.getFactoryCode();
		Long tenant = TenantContextHolder.getTenantId();
		var authentication = SecurityUtils.getAuthentication();
		var user = SecurityUtils.getUser();
		require(authentication != null && authentication.isAuthenticated() && user != null
				&& user.getId() != null && user.getId() > 0 && tenant != null && tenant > 0
				&& Objects.equals(tenant, user.getTenantId()) && !TenantContextHolder.getTenantSkip()
				&& !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && factory != null && !factory.isBlank(),
				"质量假设引用须实名登录并选择认证租户和实际工厂，禁止跳过隔离");
		R<Boolean> response;
		try {
			response = factories.getObject().check(new MdmFactoryAccessCheck().setUserId(user.getId())
					.setFactoryCode(factory).setRequiredPermission(write ? "write" : "read"));
		} catch (RuntimeException unavailable) {
			throw new CheckedException("质量假设引用的实际工厂授权暂时无法核验", unavailable);
		}
		require(response != null && response.isOk() && Boolean.TRUE.equals(response.getData()),
				"当前人员没有所需实际工厂权限，或MDM工厂授权不可核验");
	}

	private void match(Map<String, Object> row, Command command) {
		require(row != null && actor().equals(text(row, "actor_user_id"))
				&& commandHash(command).equals(text(row, "command_hash")), "原请求号已用于其他人员或完整命令，不能覆盖");
	}

	private void validate(Object value) {
		require(value != null && validator.validate(value).isEmpty(), "原质量假设、目标周期或人工命令字段不合法");
		Source source = value instanceof Command command ? command.source() : (Source) value;
		id(source.runId());
		id(source.analysisArtifactId());
		require(source.hypothesisId().matches("H[1-9][0-9]{0,2}"), "原分析假设编号不符合严格V1合同");
		if (value instanceof Command command) {
			id(command.capaId());
			validText(command.causeDescription());
			validText(command.evidence());
		}
	}

	private void validText(String value) {
		require(value != null && !value.isBlank(), "原命令或保存来源文本为空");
		// PostgreSQL text不承载NUL；正文仅保留换行、回车与TAB，坏命令不能先登记后陷入无法取消。
		require(value.codePoints().noneMatch(code -> code < 32 && code != 9 && code != 10 && code != 13
				|| code >= 127 && code <= 159), "原命令或保存来源文本含不可存储控制字符");
	}

	private void requestId(String value) {
		require(value != null && value.matches("[A-Za-z0-9._:-]{8,100}"), "原请求号不合法");
	}

	private Long id(String value) {
		require(value != null && value.matches("[1-9][0-9]{0,18}"), "原身份不是完整正整数文本");
		try {
			return Long.valueOf(value);
		} catch (NumberFormatException invalid) {
			throw new CheckedException("原主键超出合法Long范围");
		}
	}

	private String commandHash(Command command) {
		return hash(write(List.of(TenantContextHolder.getTenantId().toString(), FactoryContextHolder.getFactoryCode(),
				actor(), command)));
	}

	private String actor() {
		return SecurityUtils.getUser().getId().toString();
	}

	private String string(Map<String, Object> value, String key) {
		require(value.get(key) instanceof String, "保存来源缺少文本字段：" + key);
		return (String) value.get(key);
	}

	private String text(Map<String, Object> value, String key) {
		return Objects.toString(value.get(key), "");
	}

	private String write(Object value) {
		try {
			return json.writeValueAsString(value);
		} catch (Exception invalid) {
			throw new CheckedException("质量引用原命令或证明无法序列化");
		}
	}

	private String hash(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception failure) {
			throw new IllegalStateException("SHA-256不可用", failure);
		}
	}

	private TransactionTemplate transaction(boolean readOnly) {
		TransactionTemplate transaction = new TransactionTemplate(manager);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		transaction.setReadOnly(readOnly);
		transaction.setIsolationLevel(readOnly ? TransactionDefinition.ISOLATION_REPEATABLE_READ : TransactionDefinition.ISOLATION_READ_COMMITTED);
		return transaction;
	}

	private void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
