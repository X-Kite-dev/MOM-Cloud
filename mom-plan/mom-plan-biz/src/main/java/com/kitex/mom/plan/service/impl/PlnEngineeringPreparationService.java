package com.kitex.mom.plan.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.dto.MdmRouteEngineeringDTO;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mdm.feign.RemoteMdmRouteEngineeringService;
import com.kitex.mom.plan.dto.PlnEngineeringAllocationDTO.Observation;
import com.kitex.mom.plan.dto.PlnEngineeringPreparationDTO.*;
import com.kitex.mom.plan.entity.PlnEngineeringPreparationEntity;
import com.kitex.mom.plan.mapper.PlnEngineeringPreparationMapper;
import com.kitex.mom.plan.mapper.PlnEngineeringResolutionMapper;
import com.kitex.mom.plan.entity.PlnEngineeringResolutionEntity;
import com.kitex.mom.plan.dto.PlnEngineeringResolutionDTO;
import com.kitex.mom.plan.support.PlnEngineeringPreparationProofCodec;
import com.kitex.mom.mes.support.MesPlanEngineeringDecisionCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * PLAN自有工程准备与永久撤销。纯领域编排，不暴露单表CRUD；
 * 原准备与原件永久保留，V2按可信MES终态映射有效状态；不重写原计划、工程或实物。
 */
@Service
@RequiredArgsConstructor
public class PlnEngineeringPreparationService {

	private static final String SCHEMA = "PLAN_ENGINEERING_PREPARE_V1";
	private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS");
	private final PlnEngineeringPreparationMapper mapper;
	private final PlnEngineeringResolutionMapper resolutions;
	private final PlnEngineeringAllocationObservationService observations;
	private final RemoteMdmFactoryAccessService factories;
	private final RemoteMdmRouteEngineeringService engineering;
	private final PermissionService permissions;
	private final PlatformTransactionManager transactions;

	/**
	 * 完全未安装明确返回不支持；部分安装或失效守卫不能冒充没有准备记录。
	 */
	public Context context(String taskCode) {
		Scope scope = scope("read");
		text(taskCode, "原工单号", 100);
		if (!available()) {
			return new Context(false, taskCode, List.of(), false, false);
		}
		var rows = mapper.recent(scope.tenant(), scope.factory(), taskCode);
		require(rows != null && rows.size() <= 51, "准备记录范围不可完整核验");
		var ids = rows.stream().limit(50).map(PlnEngineeringPreparationEntity::getId).toList();
		var endings = ids.isEmpty() ? List.<PlnEngineeringResolutionEntity>of()
				: resolutions.forParents(scope.tenant(), scope.factory(), ids);
		var byParent = new HashMap<Long, PlnEngineeringResolutionEntity>();
		for (var ending : endings) {
			require(ending != null && ids.contains(ending.getPreparationId())
					&& byParent.putIfAbsent(ending.getPreparationId(), ending) == null, "原准备终结范围重复或越界");
		}
		return new Context(true, taskCode, rows.stream().limit(50)
				.map(row -> receipt(scope, row, null, byParent.get(row.getId()))).toList(), rows.size() > 50, true);
	}

	/**
	 * 原号精确回读；未查到以null表示未知，不据此允许改号重新提交。
	 */
	public Receipt request(String requestId) {
		Scope scope = scope("read");
		requestId(requestId);
		requireAvailable();
		var row = mapper.request(scope.tenant(), scope.factory(), requestId);
		return row == null ? null : receipt(scope, row,
				mapper.countScopes(scope.tenant(), scope.factory(), row.getId()));
	}

	/**
	 * 原号精确权威凭据。只从永久原观察读取原taskId/产品/映射摘要，不猜同编码新工单。
	 * 动态终结状态不参加authority摘要，重复回读仍保持同一原身份。
	 */
	public PlnEngineeringResolutionDTO.Certificate certificate(String requestId) {
		Scope scope = scope("read");
		requestId(requestId);
		requireAvailable();
		var row = mapper.request(scope.tenant(), scope.factory(), requestId);
		require(row != null && row.getPreparedAt() != null && row.getTaskId() != null
						&& row.getObservationSnapshot() != null &&
						hash(row.getObservationSnapshot()).equals(row.getObservationSnapshotHash()),
				"原号没有已提交的完整准备原件，不能出具采用凭据");
		try {
			var snapshot = JSON.readTree(row.getObservationSnapshot());
			require(snapshot != null && snapshot.isObject()
							&& row.getTaskId().toString().equals(snapshot.path("taskId").asText(null))
							&& row.getTaskCode().equals(snapshot.path("taskCode").asText(null))
							&& scope.tenant().toString().equals(snapshot.path("tenantId").asText(null))
							&& scope.factory().equals(snapshot.path("factoryCode").asText(null))
							&& row.getSourceReleaseId().toString().equals(snapshot.path("sourceReleaseId").asText(null))
							&& row.getSourceContentHash().equals(snapshot.path("sourceEngineeringHash").asText(null))
							&& row.getObservationFingerprint().equals(snapshot.path("observationFingerprint").asText(null))
							&& snapshot.path("productCode").isTextual() && snapshot.path("mappingFingerprint").isTextual(),
					"原准备观察身份与父账不一致");
			var receipt = receipt(scope, row, mapper.countScopes(scope.tenant(), scope.factory(), row.getId()));
			var value = new PlnEngineeringResolutionDTO.Certificate(receipt, row.getTaskId().toString(),
					snapshot.path("productCode").textValue(), snapshot.path("mappingFingerprint").textValue(), null);
			String fingerprint = PlnEngineeringPreparationProofCodec.hash(
					PlnEngineeringPreparationProofCodec.canonical(value));
			return new PlnEngineeringResolutionDTO.Certificate(receipt, value.taskId(), value.productCode(),
					value.mappingFingerprint(), fingerprint);
		} catch (CheckedException failure) {
			throw failure;
		} catch (Exception failure) {
			throw new CheckedException("原准备完整凭据无法解析", failure);
		}
	}

	/**
	 * 原号锁→批量全量来源锁→锁内重读，保存永久准备与精确来源集合。
	 * 外部调用不携带既有事务；预读取/正式取包不占写事务连接，最终数据库守卫再证明完整原件。
	 */
	public Receipt prepare(Command command) {
		noOuterTransaction();
		Scope scope = scope("write");
		require(permissions.hasPermission("mesInFlightSopChange:propose"), "准备需要原工程编制权限");
		validate(command);
		require(command.requestId().startsWith("PEP:" + scope.actor() + ":"), "只能提交本人原准备号");
		String canonical = canonical(command);
		Receipt replay = transaction(() -> {
			requireAvailable();
			lockRequest(scope, command.requestId());
			var row = mapper.request(scope.tenant(), scope.factory(), command.requestId());
			if (row == null) {
				return null;
			}
			verifyOriginal(row, command);
			require(scope.actor().equals(row.getApplicantId()), "原号属于其他申请人");
			return receipt(scope, row, mapper.countScopes(scope.tenant(), scope.factory(), row.getId()));
		});
		if (replay != null) {
			return replay;
		}
		Observation before = observations.observe(command.taskCode());
		verifyObservation(scope, command, before);
		resolveTarget(scope, command, before);
		List<SourceScope> sources = sources(before);
		return transaction(() -> {
			requireAvailable();
			lockRequest(scope, command.requestId());
			var row = mapper.request(scope.tenant(), scope.factory(), command.requestId());
			if (row != null) {
				verifyOriginal(row, command);
				require(scope.actor().equals(row.getApplicantId()), "原号属于其他申请人");
				return receipt(scope, row, mapper.countScopes(scope.tenant(), scope.factory(), row.getId()));
			}
			require(Objects.equals(mapper.lockSources(scope.tenant(), scope.factory(), sources), sources.size()),
					"准备未获得完整来源锁");
			String conflicting = mapper.firstActiveRequest(scope.tenant(), scope.factory(), sources);
			require(conflicting == null, "关联PLAN分配已由原号 " + conflicting + " 保护，请先核对该准备");
			Observation current = observations.observeForPreparation(command.taskCode());
			verifyObservation(scope, command, current);
			require(Objects.equals(before.taskId(), current.taskId()) && sources.equals(sources(current)),
					"准备期间原工单或完整来源范围已变化，请重新读取依据");
			String snapshot = json(current);
			require(snapshot.getBytes(StandardCharsets.UTF_8).length <= 20 * 1024 * 1024,
					"完整准备原件超过20MiB，不能截断保存");
			var prepared = base(scope, command, canonical).setStatus("PREPARED")
					.setTaskId(id(current.taskId())).setSourceReleaseId(id(current.sourceReleaseId()))
					.setSourceContentHash(current.sourceEngineeringHash()).setObservationSnapshot(snapshot)
					.setObservationSnapshotHash(hash(snapshot)).setPreparedAt(LocalDateTime.now());
			require(mapper.insert(prepared) == 1, "准备父账未完整保存");
			var scopeRows = sources.stream()
					.map(source -> new SourceScopeWithId(IdWorker.getId(), source.kind(), source.sourceId())).toList();
			require(mapper.addScopes(scope.tenant(), scope.factory(), prepared.getId(), scopeRows, scope.actorName()) ==
							sources.size(),
					"准备来源未完整保存");
			var saved = mapper.request(scope.tenant(), scope.factory(), command.requestId());
			require(saved != null && "PREPARED".equals(saved.getStatus()), "准备保存后未读取到实际状态");
			// TransactionTemplate只有提交（含延迟原件约束）成功后才向控制器返回此回执。
			return receipt(scope, saved, sources.size());
		});
	}

	/**
	 * 仅永久封存未受理原号或重放既有未受理墓碑。已准备原号必须由协调服务先取得MES终态。
	 */
	public Receipt cancel(CancelCommand command) {
		noOuterTransaction();
		Scope scope = scope("write");
		require(command != null, "请保留完整原撤销命令");
		validate(command.original());
		text(command.cancelReason(), "撤销原因", 1000);
		return transaction(() -> {
			requireAvailable();
			Command original = command.original();
			lockRequest(scope, original.requestId());
			var row = mapper.request(scope.tenant(), scope.factory(), original.requestId());
			LocalDateTime now = LocalDateTime.now();
			if (row == null) {
				require(original.requestId().startsWith("PEP:" + scope.actor() + ":"), "不能抢占他人未受理原号");
				row = base(scope, original, canonical(original)).setStatus("CANCELLED")
						.setCancelledBy(scope.actor()).setCancelledAt(now).setCancelReason(command.cancelReason());
				require(mapper.insert(row) == 1, "未受理原号未能永久封存");
			} else {
				verifyOriginal(row, original);
				require(scope.actor().equals(row.getApplicantId()) ||
								permissions.hasPermission("mesInFlightSopChange:review"),
						"仅原申请人或工程审核岗位可撤销已受理准备");
				if ("CANCELLED".equals(row.getStatus())) {
					require(scope.actor().equals(row.getCancelledBy()) &&
									command.cancelReason().equals(row.getCancelReason()),
							"原号已按既有撤销事实结束，请查询原回执，不能覆盖人员或原因");
					return receipt(scope, row, mapper.countScopes(scope.tenant(), scope.factory(), row.getId()));
				}
				throw new CheckedException("原号已准备，须先核MES永久决策后撤销，请沿原号继续核对");
			}
			var saved = mapper.request(scope.tenant(), scope.factory(), original.requestId());
			require(saved != null && "CANCELLED".equals(saved.getStatus()), "未读取到原号永久撤销状态");
			return receipt(scope, saved, mapper.countScopes(scope.tenant(), scope.factory(), saved.getId()));
		});
	}

	/**
	 * 当前认证组织与真实工厂权限；不从请求接收申请人或租户。
	 */
	Scope scope(String level) {
		require(permissions.hasPermission("mesTask:show") && permissions.hasPermission("pln_plnIssue_view")
						&& permissions.hasPermission("mesInFlightSopChange:propose", "mesInFlightSopChange:review")
						&& (!"write".equals(level) || permissions.hasPermission("pln_plnIssue_edit")),
				"当前人员缺少原计划或工程岗位权限");
		var user = SecurityUtils.getUser();
		var authentication = SecurityUtils.getAuthentication();
		Long tenant = TenantContextHolder.getTenantId();
		String factory = FactoryContextHolder.getFactoryCode();
		require(authentication != null && authentication.isAuthenticated() && user != null && user.getId() != null
				&& user.getId() > 0 && tenant != null && tenant > 0 && Objects.equals(user.getTenantId(), tenant)
				&& !TenantContextHolder.getTenantSkip() && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
				&& factory != null && !factory.isBlank(), "准备业务必须实名登录并选择实际工厂");
		text(user.getUsername(), "审计账号", 100);
		var response = factories.check(new MdmFactoryAccessCheck().setUserId(user.getId())
				.setFactoryCode(factory).setRequiredPermission(level));
		require(response != null && response.isOk() && Boolean.TRUE.equals(response.getData()),
				"当前工厂权限不可核验或不足");
		return new Scope(tenant, factory, user.getId(), user.getUsername());
	}

	/**
	 * 目录查询不得静态依赖缺失表；任意残留属于部分安装，不能显示为无业务。
	 */
	private boolean available() {
		if (!Boolean.TRUE.equals(resolutions.readyPresent())) {
			require(!Boolean.TRUE.equals(resolutions.installationObjectsPresent()),
					"工程采用协同结构部分安装，请核累计升级");
			require(!Boolean.TRUE.equals(mapper.readyPresent()) &&
							!Boolean.TRUE.equals(mapper.installationObjectsPresent()),
					"当前准备版本未接完整MES终态协同，请完成对应累计升级后同步部署");
			return false;
		}
		require(Objects.equals(resolutions.ready(), 1), "工程准备及永久终态来源保护未完整就绪，不能操作");
		return true;
	}

	private void requireAvailable() {
		require(available(), "工程准备尚未安装，请按累计V4部署后使用");
	}

	/**
	 * 所有写入事务使用RC且不跨外部预取阶段；锁内观察不再申请第二个PLAN连接。
	 */
	private <T> T transaction(Supplier<T> work) {
		var template = new TransactionTemplate(transactions);
		template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return template.execute(status -> work.get());
	}

	private void noOuterTransaction() {
		require(!TransactionSynchronizationManager.isActualTransactionActive(), "准备入口不能嵌入其他业务事务");
	}

	private void lockRequest(Scope scope, String request) {
		require(Objects.equals(mapper.lockRequest(scope.tenant(), scope.factory(), request), 1), "未获取原号事务锁");
	}

	/**
	 * 仅接受服务端完整匹配的观察，原范围/期望摘要一项漂移即拒绝，不拿时间戳作锁。
	 */
	private void verifyObservation(Scope scope, Command command, Observation observation) {
		require(observation != null && "PLAN_ENGINEERING_ALLOCATION_OBSERVATION_V1".equals(observation.schemaVersion())
						&& scope.tenant().toString().equals(observation.tenantId()) &&
						scope.factory().equals(observation.factoryCode())
						&& command.taskCode().equals(observation.taskCode()) && observation.mappingMatched()
						&& observation.dataGaps() != null && observation.dataGaps().isEmpty() && !observation.executable()
						&& observation.versionFence() == null &&
						command.expectedObservationFingerprint().equals(observation.observationFingerprint()),
				"原PLAN观察存在缺口或已变化，请重新读取后用新号准备");
	}

	/**
	 * 目标只可来自MDM正式取包，不接收客户端工程正文；准备不改原计划工程。
	 */
	private void resolveTarget(Scope scope, Command command, Observation observation) {
		var response = engineering.resolveForProduction(new MdmRouteEngineeringDTO.ProductionQuery()
				.setReleaseId(id(command.targetReleaseId())).setContentHash(command.targetContentHash())
				.setProductCode(observation.productCode()));
		var target = response == null ? null : response.getData();
		require(response != null && response.isOk() && target != null
						&& Objects.equals(target.getReleaseId(), id(command.targetReleaseId()))
						&& command.targetContentHash().equals(target.getContentHash())
						&& scope.tenant().toString().equals(target.getTenantId()) &&
						scope.factory().equals(target.getFactoryCode())
						&& observation.productCode().equals(target.getProductCode()),
				"目标正式工程当前不可生产或组织/产品不一致");
		require(!command.targetReleaseId().equals(observation.sourceReleaseId())
						|| !command.targetContentHash().equals(observation.sourceEngineeringHash()),
				"请选择不同于原冻结工程的正式目标");
	}

	/**
	 * 来源只从权威观察推导，按与PG C排序一致的ASCII类别/十进制ID字符串排序。
	 */
	private List<SourceScope> sources(Observation observation) {
		var keys = observation.facts().stream().filter(fact -> Set.of("ISSUE", "BATCH", "TASK").contains(fact.kind()))
				.map(fact -> new SourceScope(fact.kind(), id(fact.id()))).distinct()
				.sorted(Comparator.comparing(SourceScope::kind).thenComparing(source -> source.sourceId().toString()))
				.toList();
		require(!keys.isEmpty() && keys.size() <= 15000, "完整来源范围缺失或超过15000条");
		return keys;
	}

	private PlnEngineeringPreparationEntity base(Scope scope, Command command, String canonical) {
		var row = new PlnEngineeringPreparationEntity().setFactoryCode(scope.factory())
				.setRequestId(command.requestId())
				.setRequestFingerprint(hash(canonical)).setCanonicalRequest(canonical).setTaskCode(command.taskCode())
				.setTargetReleaseId(id(command.targetReleaseId())).setTargetContentHash(command.targetContentHash())
				.setObservationFingerprint(command.expectedObservationFingerprint()).setReason(command.reason())
				.setApplicantId(scope.actor());
		row.setId(IdWorker.getId());
		row.setTenantId(scope.tenant());
		row.setCreateBy(scope.actorName());
		row.setUpdateBy(scope.actorName());
		row.setCreateTime(LocalDateTime.now());
		row.setUpdateTime(row.getCreateTime());
		row.setDelFlag("0");
		return row;
	}

	/**
	 * 即使原号已经结束，也只按首次内容回读，不重新取目标或复算原命令。
	 */
	private void verifyOriginal(PlnEngineeringPreparationEntity row, Command command) {
		require(canonical(command).equals(canonical(original(row))) &&
						hash(canonical(command)).equals(row.getRequestFingerprint()),
				"原号已被不同准备内容使用，不能覆盖");
	}

	/**
	 * 列表不加载大观察；用原业务列重建规范命令并核SHA，避免为50行逐条查询。
	 */
	private Command original(PlnEngineeringPreparationEntity row) {
		Command command = new Command(row.getRequestId(), row.getTaskCode(), row.getTargetReleaseId() == null ? null
				: row.getTargetReleaseId().toString(), row.getTargetContentHash(), row.getObservationFingerprint(),
				row.getReason());
		validate(command);
		String raw = canonical(command);
		require(hash(raw).equals(row.getRequestFingerprint())
						&& (row.getCanonicalRequest() == null || raw.equals(row.getCanonicalRequest()))
						&& row.getApplicantId() != null && command.requestId().startsWith("PEP:" + row.getApplicantId() + ":"),
				"原规范命令或申请人身份不可完整核验");
		return command;
	}

	private Receipt receipt(Scope scope, PlnEngineeringPreparationEntity row, Integer count) {
		return receipt(scope, row, count, resolutions.byPreparation(scope.tenant(), scope.factory(), row.getId()));
	}

	/**
	 * 最近记录已批量读取终结索引，避免为每行重复数据库往返。
	 */
	private Receipt receipt(Scope scope, PlnEngineeringPreparationEntity row, Integer count,
	                        PlnEngineeringResolutionEntity ending) {
		require(row != null && Objects.equals(row.getTenantId(), scope.tenant()) &&
				scope.factory().equals(row.getFactoryCode())
				&& "0".equals(row.getDelFlag()) && row.getId() != null && row.getId() > 0
				&& Set.of("PREPARED", "CANCELLED").contains(Objects.toString(row.getStatus(), ""))
				&& (count == null || count >= 0 && count <= 15000), "原准备回执范围或状态不完整");
		PlnEngineeringResolutionDTO.Resolution resolution = null;
		if (ending != null) {
			var actual = MesPlanEngineeringDecisionCodec.fromSnapshot(ending.getMesReceiptSnapshot(),
					ending.getMesDecisionFingerprint());
			require(Objects.equals(row.getId(), ending.getPreparationId()) &&
							Objects.equals(scope.tenant(), ending.getTenantId())
							&& scope.factory().equals(ending.getFactoryCode()) &&
							row.getRequestId().equals(ending.getRequestId())
							&& Set.of("APPLIED", "CANCELLED").contains(Objects.toString(ending.getDecision(), ""))
							&& ending.getMesDecisionId().toString().equals(actual.id()) &&
							ending.getDecision().equals(actual.decision())
							&& row.getId().toString().equals(actual.preparationId()) &&
							scope.tenant().toString().equals(actual.tenantId())
							&& scope.factory().equals(actual.factoryCode()) && row.getRequestId().equals(actual.requestId())
							&& row.getTaskId().toString().equals(actual.taskId()) && row.getTaskCode().equals(actual.taskCode())
							&& ending.getAuthorityFingerprint().equals(actual.authorityFingerprint())
							&& ending.getActorId().toString().equals(actual.actorId()) &&
							time(ending.getDecidedAt()).equals(actual.decidedAt())
							&& ending.getReason().equals(actual.reason()),
					"原准备终结索引归属或实际终态无效");
			resolution = new PlnEngineeringResolutionDTO.Resolution(ending.getId().toString(), ending.getDecision(),
					ending.getMesDecisionId().toString(), ending.getMesDecisionFingerprint(),
					ending.getActorId().toString(),
					time(ending.getDecidedAt()), ending.getReason());
		}
		boolean cancelled = ending != null && "CANCELLED".equals(ending.getDecision());
		// V1已提交的历史准备撤销保留原事实，不补造一次MES终态；其余V2记录沿新协同合同。
		boolean legacyCancellation =
				ending == null && row.getPreparedAt() != null && "CANCELLED".equals(row.getStatus());
		String cancelledBy = row.getCancelledBy() == null ? null : row.getCancelledBy().toString();
		String cancelledAt = time(row.getCancelledAt());
		String cancelReason = row.getCancelReason();
		if (cancelled) {
			cancelledBy = ending.getActorId().toString();
			cancelledAt = time(ending.getDecidedAt());
			cancelReason = ending.getReason();
		}
		return new Receipt("PLAN_ENGINEERING_PREPARE_V2", row.getId().toString(), scope.tenant().toString(),
				scope.factory(), original(row),
				row.getRequestFingerprint(), ending == null ? row.getStatus() : ending.getDecision(),
				row.getApplicantId().toString(),
				time(row.getPreparedAt()),
				row.getSourceReleaseId() == null ? null : row.getSourceReleaseId().toString(),
				row.getSourceContentHash(),
				row.getObservationSnapshotHash(), count,
				cancelledBy, cancelledAt, cancelReason, !legacyCancellation, resolution);
	}

	private static void validate(Command command) {
		require(command != null, "请保留完整原准备命令");
		requestId(command.requestId());
		text(command.taskCode(), "原工单号", 100);
		id(command.targetReleaseId());
		require(command.targetContentHash() != null && command.targetContentHash().matches("[0-9a-f]{64}")
						&& command.expectedObservationFingerprint() != null &&
						command.expectedObservationFingerprint().matches("[0-9a-f]{64}"),
				"正式目标或原观察摘要不完整");
		text(command.reason(), "准备原因", 1000);
	}

	private static void requestId(String value) {
		require(value != null && value.length() <= 100 && value.matches("PEP:[1-9][0-9]{0,18}:[A-Za-z0-9_.:-]{8,60}"),
				"原准备号格式无效");
	}

	private static Long id(String value) {
		try {
			require(value != null && value.matches("[1-9][0-9]{0,18}"), "原身份必须为正整数文本");
			return Long.valueOf(value);
		} catch (NumberFormatException failure) {
			throw new CheckedException("原身份超过Long范围", failure);
		}
	}

	private static void text(String value, String label, int maximum) {
		require(value != null && !value.isBlank() && value.length() <= maximum,
				label + "不能为空且不可超过" + maximum + "字符");
	}

	/**
	 * 固定七字段顺序，数量/ID保持文本，后续DTO增字段不改变原号协议。
	 */
	private static String canonical(Command command) {
		var value = new LinkedHashMap<String, String>();
		value.put("schemaVersion", SCHEMA);
		value.put("requestId", command.requestId());
		value.put("taskCode", command.taskCode());
		value.put("targetReleaseId", command.targetReleaseId());
		value.put("targetContentHash", command.targetContentHash());
		value.put("expectedObservationFingerprint", command.expectedObservationFingerprint());
		value.put("reason", command.reason());
		return json(value);
	}

	private static String json(Object value) {
		try {
			return JSON.writeValueAsString(value);
		} catch (Exception failure) {
			throw new CheckedException("准备原文序列化失败", failure);
		}
	}

	private static String hash(String value) {
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception failure) {
			throw new CheckedException("准备原文摘要生成失败", failure);
		}
	}

	private static String time(LocalDateTime value) {
		return value == null ? null : TIME.format(value);
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}

	/**
	 * 经过实际身份及工厂授权的本次请求范围。
	 */
	record Scope(Long tenant, String factory, Long actor, String actorName) {
	}
}
