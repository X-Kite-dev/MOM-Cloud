package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesEngineeringLaborStandardDTO;
import com.kitex.mom.mes.entity.MesInFlightSopChangeEntity;
import com.kitex.mom.mes.entity.MesPlanEngineeringDecisionEntity;
import com.kitex.mom.mes.entity.MesPlanLaborAdoptionEntity;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesInFlightSopChangeMapper;
import com.kitex.mom.mes.mapper.MesManualLaborChangeMapper;
import com.kitex.mom.mes.mapper.MesPlanEngineeringDecisionMapper;
import com.kitex.mom.mes.mapper.MesPlanLaborAdoptionMapper;
import com.kitex.mom.mes.support.MesPlanEngineeringDecisionCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 普通PLAN工单标准工时批准层的只读与真实接收消费。只消费本地永久APPLIED决策，
 * 不调用PLAN、不写原工程或排程，也不依赖决策编排服务，避免接收事务跨域互等。
 */
@Service
@RequiredArgsConstructor
public class MesPlanLaborStandardService {
	private static final ObjectMapper JSON = new ObjectMapper()
			.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
			.registerModule(new JavaTimeModule())
			.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
			.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
	private final MesPlanEngineeringDecisionMapper decisions;
	private final MesPlanLaborAdoptionMapper adoptionMapper;
	private final MesInFlightSopChangeMapper facts;
	private final MesManualLaborChangeMapper actuals;
	private final MesEngineeringPackageService packages;
	private final MesNodeLaborChangePolicy policy;
	private final MesIssuedEngineeringSource issuedSource;

	/**
	 * 只辨认普通APS来源工单；完整映射与采用权限由原决策编排负责，不能据此直接取得采用资格。
	 */
	public boolean supports(MesTaskEntity task) {
		return task != null && task.getId() != null && task.getTenantId() != null
				&& task.getFactoryCode() != null && !MesManualTaskService.owns(task)
				&& !"MANUAL".equals(task.getResourceType()) && !"REWORK".equals(task.getResourceType())
				&& Set.of("ISSUE", "DISPATCH", "PRODUCT", "PAUSE", "FINISH", "CLOSE")
				.contains(Objects.toString(task.getStatus(), ""))
				&& (task.getApsTaskId() != null || task.getApsBatchId() != null
				|| actuals.apsLinks(task.getTenantId(), task.getFactoryCode(), task.getId()) > 0);
	}

	/**
	 * 完全未安装保留旧PLAN接收及原标准只读；任意新对象或不完整守卫均失败关闭。
	 */
	public boolean available() {
		boolean function = Boolean.TRUE.equals(decisions.readyPresent());
		boolean objects = Boolean.TRUE.equals(decisions.installationObjectsPresent());
		if (!function && !objects) {
			return false;
		}
		require(function && objects && Integer.valueOf(1).equals(decisions.ready()),
				"PLAN标准工时决策或接收来源保护未完整安装，请核对统一升级入口");
		return true;
	}

	/**
	 * 工程资料完全缺失时仍核本任务新层，不能在只读页把已生效来源降级为历史未知。
	 */
	public void assertLegacyWithoutLayer(MesTaskEntity task) {
		if (available()) {
			require(!decisions.hasTaskLayer(task.getTenantId(), task.getFactoryCode(), task.getId()),
					"原工单已有新标准批准或接收来源，工程缺失不能转为历史未知");
		}
	}

	/**
	 * 原标准视图读取完整实际节点；未安装不补造历史采用账，节点超限明确拒绝。
	 */
	public List<MesProduceTaskEntity> processes(MesTaskEntity task) {
		if (!supports(task) || !available()) {
			return List.of();
		}
		return viewProcesses(task);
	}

	/**
	 * 已在本次视图核ready，轻量核APPLIED节点及原件总量后才读取每节点完整来源。
	 */
	private List<MesProduceTaskEntity> viewProcesses(MesTaskEntity task) {
		var rows = currentProcesses(task);
		var headers = decisions.byTask(task.getTenantId(), task.getFactoryCode(), task.getCode());
		require(headers.size() <= 200, "决策历史超过200项完整上限，请按原号核对，禁止截断");
		for (var header : headers) {
			if ("APPLIED".equals(header.getDecision())) {
				require(Objects.equals(header.getTaskId(), task.getId())
								&& rows.stream().filter(row -> Objects.equals(row.getId(), header.getProcessId())
								&& Objects.equals(row.getSourceRouteNodeCode(), header.getRouteNodeCode())).count() == 1,
						"已生效标准的原工序缺失或稳定节点漂移，禁止省略批准来源");
			}
		}
		Long bytes = decisions.evidenceBytes(task.getTenantId(), task.getFactoryCode(), task.getCode());
		require(bytes != null && bytes <= 16 * 1024 * 1024,
				"本工单批准原件超过16MiB完整视图上限，请按原号核对终态与来源指纹，禁止截断");
		return rows;
	}

	/**
	 * 原标准页面的一次完整读模型。每次仅核一次目录及原下达路线，每节点只加载一次实际来源，
	 * 不按200个节点重复计算完整PG目录或解析同一大包；原标准树也只解析一次。
	 */
	public Map<String, NodeView> view(MesTaskEntity task) {
		if (!supports(task) || !available()) {
			return Map.of();
		}
		var processes = viewProcesses(task);
		issuedSource.validate(task);
		var original = read(task.getEngineeringBundleSnapshot(), 20 * 1024 * 1024).path("nodes");
		require(original.isArray() && original.size() <= 200, "原标准节点范围缺失或超限");
		Map<String, JsonNode> byNode = new HashMap<>();
		for (var node : original) {
			String code = node.path("routeNodeCode").asText();
			require(validNode(code) && byNode.putIfAbsent(code, node) == null, "原标准稳定节点身份重复或缺失");
		}
		Map<String, NodeView> result = new HashMap<>();
		for (var process : processes) {
			String code = process.getSourceRouteNodeCode();
			var node = byNode.get(code);
			require(node != null && Objects.equals(process.getRouteCode(), node.path("processCode").asText(null)),
					"实际工序与原标准稳定节点不一致");
			var value = node.get("laborStandard");
			String raw = value == null || value.isNull() ? null : value.toString();
			var effective = effective(task, process, raw, false);
			var previous = result.putIfAbsent(code,
					new NodeView(approved(task, process, effective), adoptions(effective)));
			require(previous == null, "实际工序稳定节点不唯一，不能合并显示标准来源");
		}
		return Map.copyOf(result);
	}

	/**
	 * 当前节点批准标准摘要；原标准仍由原包展示，APPLIED不冒充已发生实际接收。
	 */
	public MesEngineeringLaborStandardDTO.Approved approved(MesTaskEntity task, MesProduceTaskEntity process) {
		if (!supports(task) || !available()) {
			return null;
		}
		return approved(task, process, effective(task, process));
	}

	private MesEngineeringLaborStandardDTO.Approved approved(MesTaskEntity task, MesProduceTaskEntity process,
	                                                         Effective effective) {
		var decision = effective.decision();
		if (decision == null) {
			return null;
		}
		var standard = policy.decode(effective.standard(), task, process);
		var projection = policy.standardProjection(effective.standard(), task, process);
		return new MesEngineeringLaborStandardDTO.Approved(decision.getId().toString(),
				decision.getTargetReleaseId().toString(), decision.getTargetContentHash(),
				decision.getDecidedAt().toString(), decision.getReason(), standard.getSourceFingerprint(),
				projection.basis(), projection.measures());
	}

	/**
	 * 原实际接收的不可变摘要；无账只表示无新采用记录，不能推断历史未接收。
	 */
	public List<MesEngineeringLaborStandardDTO.Adoption> adoptions(MesTaskEntity task, MesProduceTaskEntity process) {
		if (!supports(task) || !available()) {
			return List.of();
		}
		return adoptions(effective(task, process));
	}

	private List<MesEngineeringLaborStandardDTO.Adoption> adoptions(Effective effective) {
		return effective.adoptions().stream()
				.map(row -> new MesEngineeringLaborStandardDTO.Adoption(row.getId().toString(),
						row.getArrangeId().toString(),
						row.getDecisionId() == null ? null : row.getDecisionId().toString(),
						row.getSourceFingerprint(), row.getReceivedAt().toString())).toList();
	}

	/**
	 * 在原receive事务和工单锁内，先冻结本派工来源，再由原入口保存RECEIVE；提交守卫要求两者同时成功。
	 * 同节点已有采用时后续派工只能沿第一次的同一来源；无新模块时不改变原PLAN接收。
	 */
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public void freezeForReceive(MesTaskArrangeEntity arrange) {
		var task = actuals.taskAny(arrange.getTenantId(), arrange.getFactoryCode(), arrange.getTaskCode());
		require(task != null && "0".equals(task.getDelFlag()), "派工原生产工单不存在");
		if (MesManualTaskService.owns(task) || "MANUAL".equals(task.getResourceType())
				|| "REWORK".equals(task.getResourceType()) || !available()) {
			return;
		}
		if (!supports(task) || actuals.apsLinks(task.getTenantId(), task.getFactoryCode(), task.getId()) == 0
				|| task.getEngineeringReleaseId() == null
				|| !text(task.getEngineeringContentHash()) || !text(task.getEngineeringBundleSnapshot())
				|| !validNode(arrange.getSourceRouteNodeCode())) {
			validateLegacyWithoutLayer(task, arrange);
			return;
		}
		require(Set.of("ISSUE", "DISPATCH", "PRODUCT", "PAUSE").contains(Objects.toString(task.getStatus(), "")),
				"原PLAN工单已结束或关闭，不能接收或新增采用来源");
		var matches = currentProcesses(task).stream()
				.filter(row -> Objects.equals(row.getCode(), arrange.getProduceTaskCode())).toList();
		require(matches.size() == 1, "接收派工未唯一对应原稳定节点");
		var process = matches.get(0);
		require(Objects.equals(process.getSourceRouteNodeCode(), arrange.getSourceRouteNodeCode()),
				"接收派工稳定节点归属不同");
		var effective = effective(task, process);
		var old = adoptionMapper.byArrange(task.getTenantId(), task.getFactoryCode(), arrange.getId());
		if (old != null) {
			require(effective.adoptions().stream().anyMatch(row -> Objects.equals(row.getId(), old.getId())),
					"原派工接收采用账与原节点不一致");
			return;
		}
		require("UN_RECEIVE".equals(arrange.getStatus())
						&& zero(arrange.getCompleteNum(), arrange.getUnPassNum())
						&& arrange.getStartTime() == null && arrange.getFinishTime() == null,
				"仅原未接收且零实绩派工可冻结首次采用来源");
		if (effective.decision() != null && effective.adoptions().isEmpty()) {
			validateUntouched(task, process, false);
		}
		var decision = effective.decision();
		var row = new MesPlanLaborAdoptionEntity();
		row.setId(IdWorker.getId());
		row.setTenantId(task.getTenantId());
		row.setFactoryCode(task.getFactoryCode());
		row.setTaskCode(task.getCode());
		row.setTaskId(task.getId());
		row.setProcessId(process.getId());
		row.setRouteNodeCode(process.getSourceRouteNodeCode());
		row.setArrangeId(arrange.getId());
		row.setDecisionId(decision == null ? null : decision.getId());
		row.setOriginalEngineeringHash(task.getEngineeringContentHash());
		row.setReleaseId(decision == null ? task.getEngineeringReleaseId() : decision.getTargetReleaseId());
		row.setContentHash(decision == null ? task.getEngineeringContentHash() : decision.getTargetContentHash());
		row.setLaborSnapshot(effective.standard());
		row.setSourceFingerprint(effective.standard() == null ? null
				: policy.decode(effective.standard(), task, process).getSourceFingerprint());
		LocalDateTime now = LocalDateTime.now();
		String actor = SecurityUtils.getUser().getUsername();
		require(actor != null && !actor.isBlank() && actor.length() <= 100, "接收来源审计账号缺失");
		row.setReceivedAt(now);
		row.setCreateBy(actor);
		row.setUpdateBy(actor);
		row.setCreateTime(now);
		row.setUpdateTime(now);
		row.setDelFlag("0");
		require(adoptionMapper.insert(row) == 1, "PLAN接收标准工时采用来源未保存，接收已回滚");
	}

	/**
	 * 工单锁内核目标完整执行事实。批准阶段还必须没有旧批准、接收采用、SOP执行及预投料；
	 * 首次接收只重核自身执行/占用，既有合法SOP门禁仍由原接收入口负责。
	 */
	public void validateUntouched(MesTaskEntity task, MesProduceTaskEntity process, boolean approving) {
		require(supports(task) && Objects.equals(task.getTenantId(), process.getTenantId())
						&& Objects.equals(task.getFactoryCode(), process.getFactoryCode())
						&& Objects.equals(task.getCode(), process.getTaskCode()) && "0".equals(process.getDelFlag())
						&& validNode(process.getSourceRouteNodeCode())
						&& Set.of("WAIT", "UN_RECEIVE").contains(Objects.toString(process.getStatus(), ""))
						&& zero(process.getCompleteNum(), process.getUnPassNum())
						&& process.getStartTime() == null && process.getFinishTime() == null,
				"仅普通PLAN工单首次接收前、零实绩的稳定节点可采用新标准工时");
		require(!approving || Set.of("ISSUE", "DISPATCH", "PRODUCT", "PAUSE").contains(task.getStatus()),
				"原工单已结束，不能采用新标准工时");
		var allProcesses = currentProcesses(task);
		require(allProcesses.stream().filter(row -> Objects.equals(row.getSourceRouteNodeCode(),
				process.getSourceRouteNodeCode())).count() == 1, "完整工单范围内稳定节点不唯一");
		if (approving) {
			require(decisions.applied(task.getTenantId(), task.getFactoryCode(), process.getId()) == null
							&& adoptionMapper.byProcess(task.getTenantId(), task.getFactoryCode(), process.getId()).isEmpty(),
					"本节点已有批准或首次接收采用来源，不能追溯换版");
		}
		var arranges = actuals.arranges(task.getTenantId(), task.getFactoryCode(), process.getCode());
		require(arranges.size() <= 200, "派工超过200项完整读取上限");
		for (var row : arranges) {
			require(Objects.equals(row.getTaskCode(), task.getCode())
							&& Objects.equals(row.getSourceRouteNodeCode(), process.getSourceRouteNodeCode())
							&& "0".equals(row.getDelFlag()) && "UN_RECEIVE".equals(row.getStatus())
							&& zero(row.getCompleteNum(), row.getUnPassNum())
							&& row.getStartTime() == null && row.getFinishTime() == null,
					"目标完整派工范围已接收或存在执行事实");
		}
		require(facts.reports(task.getTenantId(), task.getFactoryCode(), process.getCode()) == 0
						&& (!approving || facts.sopFacts(task.getTenantId(), task.getFactoryCode(), process.getId()) == 0)
						&& (!approving || actuals.feeds(task.getTenantId(), task.getFactoryCode(), process.getCode()) == 0)
						&& actuals.heldNode(task.getTenantId(), task.getFactoryCode(), process.getCode()) == 0,
				"原节点已有报工、SOP、投料或自身实物来源冻结事实，不能替换标准工时");
		issuedSource.validate(task);
		require(Objects.equals(process.getProcessRouteId(), task.getProcessRouteId())
						&& Objects.equals(process.getProcessRouteCode(), task.getProcessRouteCode())
						&& packages.sameProjection(process.getProcessRouteSnapshot(), task.getProcessRouteSnapshot()),
				"原工序路线与工单下达投影不同，不能采用新标准工时");
	}

	/**
	 * 原工单锁内生成完整目标范围原件；只保存实际MES范围与同源SOP，不将PLAN观察当本地事实。
	 */
	public String contextSnapshot(MesTaskEntity task, MesProduceTaskEntity process) {
		validateUntouched(task, process, true);
		var sop = compatibleSop(task, process);
		String source = JSONUtil.createObj().set("version", "MES_PLAN_LABOR_SOURCE_V1")
				.set("taskId", task.getId().toString()).set("originalEngineeringHash", task.getEngineeringContentHash())
				.set("process", process)
				.set("arranges", actuals.arranges(task.getTenantId(), task.getFactoryCode(), process.getCode()))
				.set("sopChangeId", sop == null ? null : sop.getId().toString())
				.set("sopContentHash", sop == null ? null : sop.getContentHash()).toString();
		require(source.getBytes(StandardCharsets.UTF_8).length <= 2 * 1024 * 1024,
				"完整目标范围超过2MiB，禁止截断");
		return source;
	}

	/**
	 * SOP批准与标准批准分别相对于同一原工程核允许维度，不能以另一个层的目标整包替换原包。
	 */
	public MesInFlightSopChangeEntity compatibleSop(MesTaskEntity task, MesProduceTaskEntity process) {
		var sop = facts.approved(task.getTenantId(), task.getFactoryCode(), process.getId());
		if (sop == null) {
			return null;
		}
		require(Objects.equals(sop.getTaskCode(), task.getCode())
				&& Objects.equals(sop.getRouteNodeCode(), process.getSourceRouteNodeCode())
				&& Objects.equals(hash(sop.getBundleSnapshot()), sop.getContentHash())
				&& Objects.equals(hash(sop.getSourceSnapshot()), sop.getSourceFingerprint()), "并存SOP批准来源损坏");
		var scope = read(sop.getSourceSnapshot(), 2 * 1024 * 1024);
		require(task.getEngineeringContentHash().equals(scope.path("originalEngineeringHash").asText())
				&& Objects.equals(scope.path("process").path("processRouteSnapshot").asText(null),
				process.getProcessRouteSnapshot()), "SOP与工时批准层并非基于同一原工程");
		var original = read(task.getEngineeringBundleSnapshot(), 8 * 1024 * 1024);
		var next = read(sop.getBundleSnapshot(), 8 * 1024 * 1024);
		original.remove("sopManifest");
		next.remove("sopManifest");
		require(packages.sameProjection(original.toString(), next.toString()),
				"并存SOP批准层包含非SOP变化，不能叠加工时来源");
		return sop;
	}

	/**
	 * 仅按本地不可变原件回读来源，执行后的状态与数量允许前进，工程/节点身份不能漂移。
	 */
	private Effective effective(MesTaskEntity task, MesProduceTaskEntity process) {
		issuedSource.validate(task);
		return effective(task, process, policy.labor(task.getEngineeringBundleSnapshot(), process), true);
	}

	/**
	 * 原下达校验由调用批次负责，原标准从本批解析的完整树取值；接收仍强制核并存SOP。
	 */
	private Effective effective(MesTaskEntity task, MesProduceTaskEntity process, String standard, boolean checkSop) {
		require(Objects.equals(task.getTenantId(), process.getTenantId())
						&& Objects.equals(task.getFactoryCode(), process.getFactoryCode())
						&& Objects.equals(task.getCode(), process.getTaskCode()) && validNode(process.getSourceRouteNodeCode()),
				"工时采用来源未归属原工单稳定节点");
		var decision = decisions.applied(task.getTenantId(), task.getFactoryCode(), process.getId());
		if (decision != null) {
			validateDecision(task, process, decision);
			standard = decision.getLaborSnapshot();
		}
		if (checkSop || decision != null) {
			compatibleSop(task, process);
		}
		var rows = adoptionMapper.byProcess(task.getTenantId(), task.getFactoryCode(), process.getId());
		require(rows.size() <= 200, "接收采用记录超过200项完整上限，禁止截断");
		String standardFingerprint =
				standard == null ? null : policy.decode(standard, task, process).getSourceFingerprint();
		for (var row : rows) {
			require(Objects.equals(row.getTaskId(), task.getId()) && Objects.equals(row.getTaskCode(), task.getCode())
							&& Objects.equals(row.getTenantId(), task.getTenantId())
							&& Objects.equals(row.getFactoryCode(), task.getFactoryCode())
							&& Objects.equals(row.getProcessId(), process.getId()) && "0".equals(row.getDelFlag())
							&& row.getId() != null && row.getArrangeId() != null && row.getReceivedAt() != null
							&& Objects.equals(row.getRouteNodeCode(), process.getSourceRouteNodeCode())
							&& Objects.equals(row.getOriginalEngineeringHash(), task.getEngineeringContentHash())
							&& Objects.equals(row.getDecisionId(), decision == null ? null : decision.getId())
							&& Objects.equals(row.getReleaseId(), decision == null
							? task.getEngineeringReleaseId() : decision.getTargetReleaseId())
							&& Objects.equals(row.getContentHash(), decision == null
							? task.getEngineeringContentHash() : decision.getTargetContentHash())
							&& Objects.equals(row.getLaborSnapshot(), standard)
							&& Objects.equals(row.getSourceFingerprint(), standardFingerprint),
					"接收时标准工时采用来源与原包或批准层不一致，禁止改用其他版本");
		}
		return new Effective(standard, decision, rows);
	}

	/**
	 * 核永久决策的规范回执、授权、原范围及目标包字节SHA，并逐项核对真实工单/节点归属。
	 */
	private void validateDecision(MesTaskEntity task, MesProduceTaskEntity process,
	                              MesPlanEngineeringDecisionEntity decision) {
		String[] originals = {decision.getBundleSnapshot(), decision.getSourceSnapshot(), decision.getLaborSnapshot(),
				decision.getAuthoritySnapshot(), decision.getCommandSnapshot(), decision.getReceiptSnapshot()};
		require(Arrays.stream(originals).allMatch(Objects::nonNull), "本节点批准原件不完整，禁止替代来源");
		long bytes = Arrays.stream(originals).mapToLong(value -> value.getBytes(StandardCharsets.UTF_8).length).sum();
		require(bytes <= 20 * 1024 * 1024, "本节点标准批准原件超过20MiB完整读取上限");
		var receipt = MesPlanEngineeringDecisionCodec.fromSnapshot(decision.getReceiptSnapshot(),
				decision.getReceiptFingerprint());
		JsonNode receiptNode = JSON.valueToTree(receipt);
		JsonNode entityNode = JSON.valueToTree(decision);
		for (String field : List.of("id", "tenantId", "factoryCode", "preparationId", "requestId",
				"authorityFingerprint", "taskId", "taskCode", "productCode", "sourceReleaseId", "sourceContentHash",
				"targetReleaseId", "targetContentHash", "decision", "actorId", "reason", "processId", "routeNodeCode",
				"processCode", "sourceFingerprint", "commandFingerprint", "receiptFingerprint")) {
			require(Objects.equals(receiptNode.path(field).asText(null), entityNode.path(field).asText(null)),
					"永久工时决策规范回执与原账不一致：" + field);
		}
		require("APPLIED".equals(decision.getDecision()) && "0".equals(decision.getDelFlag())
						&& Objects.equals(decision.getTenantId(), task.getTenantId())
						&& Objects.equals(decision.getFactoryCode(), task.getFactoryCode())
						&& Objects.equals(decision.getTaskId(), task.getId())
						&& Objects.equals(decision.getTaskCode(), task.getCode())
						&& Objects.equals(decision.getProductCode(), task.getMatCode())
						&& Objects.equals(decision.getSourceReleaseId(), task.getEngineeringReleaseId())
						&& Objects.equals(decision.getSourceContentHash(), task.getEngineeringContentHash())
						&& Objects.equals(decision.getProcessId(), process.getId())
						&& Objects.equals(decision.getRouteNodeCode(), process.getSourceRouteNodeCode())
						&& Objects.equals(decision.getProcessCode(), process.getRouteCode())
						&& Objects.equals(hash(decision.getAuthoritySnapshot()), decision.getAuthorityFingerprint())
						&& Objects.equals(hash(decision.getCommandSnapshot()), decision.getCommandFingerprint())
						&& Objects.equals(hash(decision.getSourceSnapshot()), decision.getSourceFingerprint())
						&& Objects.equals(hash(decision.getLaborSnapshot()), receipt.laborFingerprint())
						&& Objects.equals(decision.getDecidedAt(), LocalDateTime.parse(receipt.decidedAt())),
				"批准层原工单、节点或原件指纹损坏");
		var authority = read(decision.getAuthoritySnapshot(), 64 * 1024);
		for (String field : List.of("preparationId", "requestId", "tenantId", "factoryCode", "taskId", "taskCode",
				"productCode", "sourceReleaseId", "sourceContentHash", "targetReleaseId", "targetContentHash")) {
			require(Objects.equals(authority.path(field).asText(null), entityNode.path(field).asText(null)),
					"PLAN授权原件与批准层身份不一致：" + field);
		}
		require("PLAN_ENGINEERING_PREPARATION_AUTHORITY_V1".equals(authority.path("schemaVersion").asText()),
				"PLAN授权原件协议不一致");
		var scope = read(decision.getSourceSnapshot(), 2 * 1024 * 1024);
		var originalProcess = scope.path("process");
		require("MES_PLAN_LABOR_SOURCE_V1".equals(scope.path("version").asText())
				&& Objects.equals(scope.path("taskId").asText(null), task.getId().toString())
				&& Objects.equals(scope.path("originalEngineeringHash").asText(null), task.getEngineeringContentHash())
				&& Objects.equals(originalProcess.path("id").asText(null), process.getId().toString())
				&& Objects.equals(originalProcess.path("taskCode").asText(null), task.getCode())
				&& Objects.equals(originalProcess.path("sourceRouteNodeCode").asText(null),
				process.getSourceRouteNodeCode())
				&& Objects.equals(originalProcess.path("processRouteSnapshot").asText(null),
				process.getProcessRouteSnapshot())
				&& scope.path("arranges").isArray(), "批准层原工序范围或原路线来源损坏");
		policy.compare(task, process, decision.getBundleSnapshot(), decision.getTargetContentHash());
		require(Objects.equals(policy.labor(decision.getBundleSnapshot(), process), decision.getLaborSnapshot()),
				"批准标准来源与完整目标原件不同");
	}

	/**
	 * 旧PLAN没有正式联合包或稳定节点时沿原接收；任何新批准/采用层均禁止借缺证降级绕过。
	 */
	private void validateLegacyWithoutLayer(MesTaskEntity task, MesTaskArrangeEntity arrange) {
		require(!decisions.hasTaskLayer(task.getTenantId(), task.getFactoryCode(), task.getId())
						&& adoptionMapper.byArrange(task.getTenantId(), task.getFactoryCode(), arrange.getId()) == null,
				"原PLAN工单已有新标准批准或接收来源，不能借工程或节点缺证退回历史合同");
	}

	/**
	 * 单节点采用及接收只取完整实际节点目录，不加载其他节点批准大包。
	 */
	private List<MesProduceTaskEntity> currentProcesses(MesTaskEntity task) {
		var rows = facts.processes(task.getTenantId(), task.getFactoryCode(), task.getCode());
		require(rows.size() <= 200, "实际节点超过200项完整上限，禁止截断标准来源");
		return rows;
	}

	private ObjectNode read(String raw, int maximumBytes) {
		require(raw != null && raw.getBytes(StandardCharsets.UTF_8).length <= maximumBytes,
				"来源原件缺失或超过完整读取上限，禁止截断");
		try {
			var value = JSON.readTree(raw);
			require(value instanceof ObjectNode, "来源原件不是对象");
			return (ObjectNode) value;
		} catch (CheckedException failure) {
			throw failure;
		} catch (Exception failure) {
			throw new CheckedException("来源原件无法完整解析", failure);
		}
	}

	private String hash(String value) {
		require(value != null, "来源原件不能为空");
		return MesPlanEngineeringDecisionCodec.hash(value);
	}

	private boolean zero(BigDecimal... values) {
		return Arrays.stream(values).allMatch(value -> value != null && value.signum() == 0);
	}

	private boolean text(String value) {
		return value != null && !value.isBlank();
	}

	private boolean validNode(String code) {
		return code != null && code.matches("RN-[1-9][0-9]{0,18}");
	}

	private void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}

	/**
	 * 同节点实际来源；采用原标准时decision为空，历史未知标准不补造已知值。
	 */
	private record Effective(String standard, MesPlanEngineeringDecisionEntity decision,
	                         List<MesPlanLaborAdoptionEntity> adoptions) {
	}

	/**
	 * 一次视图读取同时投影批准层和真实接收摘要，避免分别加载同一节点完整证据。
	 */
	public record NodeView(
			/** 本节点实际APPLIED批准层；无新批准时为空。 */
			MesEngineeringLaborStandardDTO.Approved approved,
			/** 本节点实际接收时不可变采用来源；历史无账保持空。 */
			List<MesEngineeringLaborStandardDTO.Adoption> adoptions) {
	}
}
