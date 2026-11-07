package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.dto.MdmRouteEngineeringDTO;
import com.kitex.mom.mdm.feign.RemoteMdmRouteEngineeringService;
import com.kitex.mom.mes.dto.MesInFlightEngineeringImpactDTO.*;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.mapper.MesInFlightEngineeringImpactMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * 在制工程影响只读编排。原包来自已下达工单，目标包由实名MDM正式版本对精确取回；
 * 本地执行事实在同一可重复读快照内观察，不伪装成跨域一致性fence，也不修改原事实。
 */
@Service
@RequiredArgsConstructor
public class MesInFlightEngineeringImpactService {
	private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
			.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
			.enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
	private final MesActualFactoryAccessService access;
	private final MesInFlightEngineeringImpactMapper mapper;
	private final MesEngineeringPackageService packages;
	private final RemoteMdmRouteEngineeringService engineering;
	private final MesEngineeringMaterialScope materialScopes;
	private final PlatformTransactionManager transactions;

	/**
	 * 返回当前产品正式候选；不沿用SOP-only对同路线/BOM的限制，也不赋予变更资格。
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public List<MdmRouteEngineeringDTO.ProductionCandidate> candidates(String taskCode) {
		var authorized = authorize();
		var task = localRead(() -> task(taskCode, authorized));
		var response = engineering.productionCandidates(task.getMatCode());
		require(response != null && response.isOk() && response.getData() != null
				&& response.getData().size() <= 100, "正式工程候选不可完整核验，请稍后重试");
		require(response.getData().stream().allMatch(row -> row != null && row.getReleaseId() != null
						&& row.getRouteId() != null && row.getBomId() != null && row.getContentHash() != null
						&& row.getContentHash().matches("[0-9a-f]{64}") && task.getMatCode().equals(row.getProductCode())),
				"正式工程候选身份、指纹或产品范围不完整");
		var refreshed = authorize();
		localRead(() -> current(taskCode, task, refreshed));
		return response.getData();
	}

	/**
	 * 生成完整节点/全包差异与真实执行观察；大包、超量事实或原件不完整时拒绝，不截断。
	 * 即使没有任何差异仍固定executable=false，缺失提供方证据不由客户端JSON补齐。
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public Preview preview(String taskCode, String releaseId, String contentHash) {
		var authorized = authorize();
		var task = localRead(() -> task(taskCode, authorized));
		var query = new MesTaskReceiveDTO();
		query.setEngineeringReleaseId(id(releaseId));
		query.setEngineeringContentHash(contentHash);
		query.setMatCode(task.getMatCode());
		query.setFactoryCode(task.getFactoryCode());
		var target = packages.resolve(query);
		require(task.getTenantId().toString().equals(target.source().getTenantId()), "目标工程租户与原工单不一致");
		// MDM等待发生在本地事务之外；回到新RR快照后重新核组织及原来源，不能拼接旧工单事实。
		var refreshed = authorize();
		return localRead(() -> preview(current(taskCode, task, refreshed), target));
	}

	/**
	 * 同一个本地RR快照读取原工单和全部执行事实，目标仅为已精确取回的正式原件。
	 */
	private Preview preview(MesTaskEntity task, MesEngineeringPackageService.FrozenEngineering target) {
		var original = packages.readIssuedOriginal(task);
		JsonNode before = read(original.source().getBundleSnapshot());
		JsonNode after = read(target.source().getBundleSnapshot());
		var oldNodes = index(before.path("nodes"));
		var newNodes = index(after.path("nodes"));
		var oldGraph = index(before.path("graph").path("procedureList"));
		var newGraph = index(after.path("graph").path("procedureList"));
		var nodeCodes = new TreeSet<String>();
		nodeCodes.addAll(oldNodes.keySet());
		nodeCodes.addAll(newNodes.keySet());
		var processes = mapper.processes(task.getTenantId(), task.getFactoryCode(), task.getCode());
		var arranges = mapper.arranges(task.getTenantId(), task.getFactoryCode(), task.getCode());
		var reports = mapper.reports(task.getTenantId(), task.getFactoryCode(), task.getCode());
		require(processes.size() <= 1000 && arranges.size() <= 10000 && reports.size() <= 20000,
				"工单事实超过预览完整读取上限，禁止截断后推断未开工");
		var facts = facts(nodeCodes, processes, arranges, reports);
		var nodes = new ArrayList<NodeImpact>();
		for (String code : nodeCodes) {
			var selected = processes.stream().filter(row -> code.equals(row.getSourceRouteNodeCode())).toList();
			var nodeFacts = facts.bound().getOrDefault(code, List.of());
			var reasons = new ArrayList<String>();
			var state = execution(code, selected, arranges, reports, reasons);
			if (!facts.unbound().isEmpty()) {
				state = ExecutionState.MISSING_EVIDENCE;
				reasons.add("工单存在未能唯一绑定稳定节点的工序、派工或报工，范围完整性待核");
			}
			if (selected.size() == 1 && oldNodes.containsKey(code)
					&&
					!Objects.equals(selected.get(0).getRouteCode(), oldNodes.get(code).path("processCode").asText())) {
				state = ExecutionState.MISSING_EVIDENCE;
				reasons.add("真实工序的主工序编码与原稳定节点不一致");
			}
			var differences = List.of(
					difference(Category.MATERIAL, materials(before, oldNodes.get(code), code),
							materials(after, newNodes.get(code), code)),
					difference(Category.GRAPH, oldGraph.get(code), newGraph.get(code)),
					difference(Category.LABOR_STANDARD, labor(before, oldNodes.get(code)),
							labor(after, newNodes.get(code))));
			nodes.add(new NodeImpact(code, processCode(oldNodes.get(code)), processCode(newNodes.get(code)),
					state, List.copyOf(reasons), nodeFacts, differences));
		}
		var sourceRef = reference(original.source());
		var targetRef = reference(target.source());
		var materialScope = materialScopes.project(task, before, after, sourceRef, targetRef);
		var scope = new EvidenceScope(task.getTenantId().toString(), task.getFactoryCode(), task.getId().toString(),
				task.getCode(), sourceRef, targetRef, List.copyOf(nodeCodes), materialScope.fingerprint());
		return new Preview("MES_INFLIGHT_ENGINEERING_IMPACT_V1", false, task.getCode(), Instant.now(), sourceRef,
				targetRef,
				List.copyOf(nodes), List.of(difference(Category.BOM, bom(before), bom(after)),
				difference(Category.GRAPH, before.path("graph"), after.path("graph")),
				difference(Category.OTHER_ENGINEERING, before, after)), facts.unbound(), requirements(scope),
				List.of("完整工程换版仅作预览；材料、BOM及工序图实际换版尚未开放",
						"标准工时单节点采用走原独立流程，不代表完整工程或APS换版",
						"尚未接入WMS全量预留/实物负向证据、PLAN完整资源分配版本栅栏和MES执行水位",
						"本地无报工或无投料不能证明WMS无占用；客户端JSON不能作为提供方证据",
						"已完成、冲销及当前执行事实全部保留；本预览不会重绑原包或迁移份额"), materialScope);
	}

	/**
	 * 远程返回后重读真实工单，源包、产品、路线/BOM及总需求变化须重新查询。
	 * 状态仍按当前已下达范围校验，执行事实全部来自本次新快照，不借初读状态推断未开工。
	 */
	private MesTaskEntity current(String code, MesTaskEntity previous, AuthorizedScope authorized) {
		var current = task(code, authorized);
		require(Objects.equals(previous.getId(), current.getId())
						&& Objects.equals(previous.getTenantId(), current.getTenantId())
						&& Objects.equals(previous.getFactoryCode(), current.getFactoryCode())
						&& Objects.equals(previous.getMatCode(), current.getMatCode())
						&& Objects.equals(previous.getEngineeringReleaseId(), current.getEngineeringReleaseId())
						&& Objects.equals(previous.getEngineeringContentHash(), current.getEngineeringContentHash())
						&& Objects.equals(previous.getEngineeringBundleSnapshot(), current.getEngineeringBundleSnapshot())
						&& Objects.equals(previous.getEngineeringVersionNo(), current.getEngineeringVersionNo())
						&& Objects.equals(previous.getProcessRouteId(), current.getProcessRouteId())
						&& Objects.equals(previous.getProcessRouteCode(), current.getProcessRouteCode())
						&& Objects.equals(previous.getBomId(), current.getBomId())
						&& Objects.equals(previous.getBomVersion(), current.getBomVersion())
						&& (previous.getNoticeNum() == null ? current.getNoticeNum() == null
						: current.getNoticeNum() != null && previous.getNoticeNum().compareTo(current.getNoticeNum()) == 0),
				"查询期间工单来源或总需求发生变化，请刷新后重新选择工程版本");
		return current;
	}

	/**
	 * 独立的短只读RR快照；显式模板避免同类调用事务注解不生效，也不持连接等待MDM。
	 */
	private <T> T localRead(Supplier<T> action) {
		var template = new TransactionTemplate(transactions);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
		template.setReadOnly(true);
		return template.execute(status -> action.get());
	}

	/**
	 * 实际组织和已下达普通工单范围；工厂请求头必须经过真实授权校验。
	 */
	private MesTaskEntity task(String taskCode, AuthorizedScope authorized) {
		String factory = authorized.factory();
		Long tenant = authorized.tenant();
		require(Objects.equals(tenant, TenantContextHolder.getTenantId())
				&& factory.equals(FactoryContextHolder.getFactoryCode()), "材料预览的已授权组织上下文已变化");
		require(taskCode != null && !taskCode.isBlank() && taskCode.length() <= 100, "请选择当前组织实际工单");
		var task = mapper.task(tenant, factory, taskCode);
		require(task != null && Objects.equals(tenant, task.getTenantId()) && factory.equals(task.getFactoryCode()),
				"当前组织未找到工单");
		require(Set.of("ISSUE", "DISPATCH", "PRODUCT", "PAUSE", "FINISH")
				.contains(Objects.toString(task.getStatus(), ""))
				&& !"REWORK".equals(task.getResourceType()), "仅已下达普通生产工单可查看工程影响");
		return task;
	}

	/**
	 * MDM工厂权限检查本身也是远程调用，必须在每段本地快照开始前完成，保留前后两次授权。
	 */
	private AuthorizedScope authorize() {
		String factory = access.require("read");
		return new AuthorizedScope(TenantContextHolder.getTenantId(), factory);
	}

	/**
	 * 当前实名已通过读取授权的实际组织；只在本次请求内部传递，不接受客户端上传。
	 */
	private record AuthorizedScope(
			/** 实际认证租户。 */Long tenant,
			/** 刚经MDM核验的实际工厂。 */String factory) {
	}

	/**
	 * 原件保持原hash；解析只用于展示投影，任何来源缺损不借今日MDM回填。
	 */
	private JsonNode read(String raw) {
		require(raw != null && raw.getBytes(StandardCharsets.UTF_8).length <= 8 * 1024 * 1024,
				"工程原件缺失或超过8MiB，禁止截断");
		try {
			var root = JSON.readTree(raw);
			require(root != null && root.isObject(), "工程原件不是完整对象");
			return root;
		} catch (CheckedException failure) {
			throw failure;
		} catch (Exception failure) {
			throw new CheckedException("工程原件无法解析", failure);
		}
	}

	/**
	 * 稳定节点唯一且完整，不把重复主工序视为同一个节点。
	 */
	private Map<String, JsonNode> index(JsonNode rows) {
		require(rows.isArray() && !rows.isEmpty() && rows.size() <= 200, "原件稳定节点范围缺失或超限");
		var result = new LinkedHashMap<String, JsonNode>();
		for (JsonNode row : rows) {
			String code = row.path("routeNodeCode").asText();
			require(code.matches("RN-[1-9][0-9]{0,18}") && result.putIfAbsent(code, row) == null,
					"工程稳定节点缺失或重复");
		}
		return result;
	}

	/**
	 * 逐料保留原完整policy及节点设置/适用声明；缺字段和null保持区别。
	 */
	private JsonNode materials(JsonNode root, JsonNode node, String code) {
		if (node == null) {
			return null;
		}
		var value = JSON.createObjectNode();
		for (String field : List.of("materials", "setting")) {
			if (node.has(field)) {
				value.set(field, node.get(field));
			}
		}
		var declarations = value.putArray("materialDeclarations");
		for (JsonNode row : root.path("declarations")) {
			if (code.equals(row.path("routeNodeCode").asText()) && "MATERIAL".equals(row.path("category").asText())) {
				declarations.add(row);
			}
		}
		return value;
	}

	/**
	 * 标准工时未知明确标记UNKNOWN；无单位转换、批量默认或实际工时推算。
	 */
	private JsonNode labor(JsonNode root, JsonNode node) {
		if (node == null) {
			return null;
		}
		var value = JSON.createObjectNode();
		value.put("knowledge", node.hasNonNull("laborStandard") ? "FROZEN_SOURCE" : "UNKNOWN");
		if (root.has("laborStandardSchemaVersion")) {
			value.set("schemaVersion", root.get("laborStandardSchemaVersion"));
		}
		if (node.has("laborStandard")) {
			value.set("source", node.get("laborStandard"));
			// 原来源字节与hash保留，同时展开原标准字段，逐项展示单位/批量和精确数值差异。
			value.set("values", read(node.path("laborStandard").path("sourceSnapshot").textValue()));
		}
		return value;
	}

	/**
	 * 完整BOM主身份、来源和子树；不只比较当前节点引用的首层材料。
	 */
	private JsonNode bom(JsonNode root) {
		return snapshot("bom", root.path("bom"), "bomStructure", root.path("bomStructure"));
	}

	/**
	 * 结构差异按JSON Pointer展开，BigDecimal比较避免浮点丢失和2/2.0伪变化。
	 */
	private Difference difference(Category category, JsonNode before, JsonNode after) {
		var fields = new ArrayList<FieldChange>();
		diff("", before, after, fields, 0);
		return new Difference(category, !fields.isEmpty(), raw(before), raw(after), List.copyOf(fields));
	}

	/**
	 * 不截断字段差异；异常深度或规模明确拒绝，避免以部分结果声称完整。
	 */
	private void diff(String path, JsonNode before, JsonNode after, List<FieldChange> fields, int depth) {
		require(depth <= 100 && fields.size() <= 20000, "工程差异层级或数量超限，禁止截断");
		if (before != null && after != null && before.isObject() && after.isObject()) {
			var names = new TreeSet<String>();
			before.fieldNames().forEachRemaining(names::add);
			after.fieldNames().forEachRemaining(names::add);
			for (String name : names) {
				diff(path + "/" + name.replace("~", "~0").replace("/", "~1"), before.get(name), after.get(name), fields,
						depth + 1);
			}
			return;
		}
		if (before != null && after != null && before.isArray() && after.isArray()) {
			for (int i = 0; i < Math.max(before.size(), after.size()); i++) {
				diff(path + "/" + i, before.get(i), after.get(i), fields, depth + 1);
			}
			return;
		}
		boolean equal = before == null ? after == null : before.isNumber() && after != null && after.isNumber()
				? before.decimalValue().compareTo(after.decimalValue()) == 0 : before.equals(after);
		if (!equal) {
			require(fields.size() < 20000, "工程字段差异超过20000项，禁止截断");
			fields.add(new FieldChange(path, before != null, after != null, raw(before), raw(after)));
		}
	}

	/**
	 * 真实工序、派工、报工按精确工序码归属稳定节点，孤立/歧义事实独立保留。
	 */
	private FactIndex facts(Set<String> nodeCodes, List<MesProduceTaskEntity> processes,
	                        List<MesTaskArrangeEntity> arranges, List<MesTaskReportEntity> reports) {
		var byProcess = new LinkedHashMap<String, List<MesProduceTaskEntity>>();
		var bound = new LinkedHashMap<String, List<ExecutionFact>>();
		var unbound = new ArrayList<ExecutionFact>();
		for (var row : processes) {
			byProcess.computeIfAbsent(row.getCode(), key -> new ArrayList<>()).add(row);
			add(bound, unbound, row.getSourceRouteNodeCode() != null &&
							nodeCodes.contains(row.getSourceRouteNodeCode()) ? row.getSourceRouteNodeCode() : null,
					new ExecutionFact("PROCESS", textId(row.getId()), row.getCode(), row.getStatus(), raw(snapshot(
							"sourceRouteNodeCode", row.getSourceRouteNodeCode(), "routeCode", row.getRouteCode(),
							"delFlag", row.getDelFlag(),
							"noticeNum", row.getNoticeNum(), "completeNum", row.getCompleteNum(), "unPassNum",
							row.getUnPassNum(),
							"startTime", row.getStartTime(), "finishTime", row.getFinishTime(), "updateTime",
							row.getUpdateTime(),
							"predecessorNodeCodes", row.getPredecessorNodeCodes(), "successorNodeCodes",
							row.getSuccessorNodeCodes(),
							"nodeTimingRulesJson", row.getNodeTimingRulesJson()))));
		}
		for (var row : arranges) {
			String node = uniqueNode(byProcess, row.getProduceTaskCode(), nodeCodes);
			if (!Objects.equals(node, row.getSourceRouteNodeCode())) {
				node = null;
			}
			add(bound, unbound, node,
					new ExecutionFact("ARRANGE", textId(row.getId()), row.getCode(), row.getStatus(), raw(snapshot(
							"produceTaskCode", row.getProduceTaskCode(), "sourceRouteNodeCode",
							row.getSourceRouteNodeCode(), "delFlag", row.getDelFlag(),
							"noticeNum", row.getNoticeNum(), "completeNum", row.getCompleteNum(), "unPassNum",
							row.getUnPassNum(),
							"startTime", row.getStartTime(), "finishTime", row.getFinishTime(), "updateTime",
							row.getUpdateTime(),
							"actualWorksiteCode", row.getActualWorksiteCode()))));
		}
		for (var row : reports) {
			add(bound, unbound, uniqueNode(byProcess, row.getProduceTaskCode(), nodeCodes),
					new ExecutionFact("REPORT", textId(row.getId()), row.getRequestId(), row.getStatus(), raw(snapshot(
							"produceTaskCode", row.getProduceTaskCode(), "arrangeCode", row.getArrangeCode(),
							"reportNum", row.getReportNum(), "effectiveReportNum", row.getEffectiveReportNum(),
							"reportUnit", row.getReportUnit(),
							"reportTime", row.getReportTime(), "timingAcceptedAt", row.getTimingAcceptedAt(),
							"reverseRequestId", row.getReverseRequestId(), "reverseTime", row.getReverseTime(),
							"delFlag", row.getDelFlag()))));
		}
		bound.replaceAll((key, value) -> List.copyOf(value));
		return new FactIndex(bound, List.copyOf(unbound));
	}

	/**
	 * 只有一个真实工序且原节点在源/目标范围内时才能绑定，不取重复码首条。
	 */
	private String uniqueNode(Map<String, List<MesProduceTaskEntity>> byProcess, String code, Set<String> nodes) {
		if (code == null || code.isBlank()) {
			return null;
		}
		var matches = byProcess.getOrDefault(code, List.of());
		return matches.size() == 1 && matches.get(0).getSourceRouteNodeCode() != null
				&& nodes.contains(matches.get(0).getSourceRouteNodeCode())
				? matches.get(0).getSourceRouteNodeCode() : null;
	}

	/**
	 * 未能绑定的真实记录保留在独立清单，使缺证显式可见。
	 */
	private void add(Map<String, List<ExecutionFact>> bound, List<ExecutionFact> unbound, String node,
	                 ExecutionFact fact) {
		if (node == null) {
			unbound.add(fact);
		} else {
			bound.computeIfAbsent(node, key -> new ArrayList<>()).add(fact);
		}
	}

	/**
	 * 保守分类真实执行事实；完成须有完工时刻、原合格完成量守恒和无活动派工；NG独立保留，不加作合格量。
	 */
	private ExecutionState execution(String code, List<MesProduceTaskEntity> selected,
	                                 List<MesTaskArrangeEntity> arranges,
	                                 List<MesTaskReportEntity> reports, List<String> reasons) {
		if (selected.size() != 1) {
			reasons.add("稳定节点" + code + "没有唯一MES工序出生事实（新增节点亦不推断为未开工）");
			return ExecutionState.MISSING_EVIDENCE;
		}
		var row = selected.get(0);
		if (!"0".equals(row.getDelFlag()) || row.getCode() == null || row.getCode().isBlank()) {
			reasons.add("原工序已软删除或缺少实际工序任务号，不能证明当前执行范围");
			return ExecutionState.MISSING_EVIDENCE;
		}
		var dispatches = arranges.stream().filter(item -> Objects.equals(row.getCode(), item.getProduceTaskCode()))
				.toList();
		var reported = reports.stream().filter(item -> Objects.equals(row.getCode(), item.getProduceTaskCode()))
				.toList();
		if (dispatches.stream().anyMatch(item -> !"0".equals(item.getDelFlag()))) {
			reasons.add("存在软删除历史派工，原事实已保留，当前执行范围完整性需核对");
			return ExecutionState.MISSING_EVIDENCE;
		}
		if (!quantity(row.getNoticeNum()) || !quantity(row.getCompleteNum()) || !quantity(row.getUnPassNum())
				|| dispatches.stream()
				.anyMatch(item -> !quantity(item.getCompleteNum()) || !quantity(item.getUnPassNum()))) {
			reasons.add("原工序或派工数量缺失/为负，不能用默认0分类");
			return ExecutionState.MISSING_EVIDENCE;
		}
		if ("FINISH".equals(row.getStatus())) {
			if (row.getFinishTime() != null && row.getNoticeNum().signum() > 0
					&& row.getCompleteNum().compareTo(row.getNoticeNum()) == 0
					&& dispatches.stream()
					.allMatch(item -> Set.of("FINISH", "CLOSE").contains(Objects.toString(item.getStatus(), "")))) {
				reasons.add("原工序已完工且数量及派工终态相符；保留原工程和全部完成/冲销事实");
				return ExecutionState.COMPLETED;
			}
			reasons.add("原工序标记完工但缺少完工时刻、数量或派工终态佐证");
			return ExecutionState.MISSING_EVIDENCE;
		}
		if (row.getStartTime() != null || row.getFinishTime() != null || row.getCompleteNum().signum() > 0
				|| row.getUnPassNum().signum() > 0 || !reported.isEmpty()
				|| Set.of("RECEIVE", "PRODUCT", "PAUSE").contains(Objects.toString(row.getStatus(), ""))
				|| dispatches.stream().anyMatch(item -> item.getStartTime() != null || item.getFinishTime() != null
				|| item.getCompleteNum().signum() > 0 || item.getUnPassNum().signum() > 0
				|| Set.of("RECEIVE", "PRODUCT", "PAUSE", "FINISH").contains(Objects.toString(item.getStatus(), "")))) {
			reasons.add("存在接收、执行、时间、数量或历史报工事实（包括已冲销）；不追溯改写");
			return ExecutionState.IN_PROGRESS;
		}
		if (Set.of("WAIT", "UN_RECEIVE").contains(Objects.toString(row.getStatus(), ""))
				&& dispatches.stream().allMatch(item -> "UN_RECEIVE".equals(item.getStatus()))) {
			reasons.add("本地完整读取的工序及派工尚未接收、零实绩且无报工；仍缺跨域占用及生效水位证明");
			return ExecutionState.NOT_STARTED;
		}
		reasons.add("原状态无法证明已完成或未开工，需核对原执行记录");
		return ExecutionState.MISSING_EVIDENCE;
	}

	/**
	 * 所有提供方均待接入；字段置null表达未知，绝不伪造无预留或完整分配版本。
	 */
	private List<ProviderRequirement> requirements(EvidenceScope scope) {
		return List.of(
				new ProviderRequirement(ProviderType.WMS_RESERVATION_AND_PHYSICAL,
						EvidenceStatus.REQUIRED_NOT_CONNECTED, scope,
						List.of("全量reservation及预留明细", "领退料、在途、线边实物、批次/单件/载具及占用处置",
								"完整BOM及逐料policy影响范围"),
						"所属WMS按完整组织/工单/节点/物料范围给出含零结果的负向原件、全量性及截止版本证明",
						null, null, null, null, null, false,
						"WMS实名读取完整预留和实物证据的实际工厂权限；本预览不代授予"),
				new ProviderRequirement(ProviderType.PLAN_RESOURCE_ALLOCATION, EvidenceStatus.REQUIRED_NOT_CONNECTED,
						scope,
						List.of("工位/并行槽位/共享资源组", "设备/人员/工装/模具/寿命预占", "全部排程分配及有效窗口"),
						"所属PLAN提供完整分配原件和可校验版本fence，不能用MES旧APS投影代表最新完整分配",
						null, null, null, null, null, false,
						"PLAN实名读取全资源分配及版本fence的实际工厂权限；本预览不代授予"),
				new ProviderRequirement(ProviderType.MES_EXECUTION_WATERMARK, EvidenceStatus.REQUIRED_NOT_CONNECTED,
						scope,
						List.of("接收/开工/暂停/完成/报工及冲销", "SOP签收、投料及实物来源占用",
								"并发命令/事件接受水位"),
						"MES需提供完整执行原件、接受水位与生效瞬间可校验fence；本次本地快照只用于阅读，未接feed不能证明WMS无占用",
						null, null, null, null, null, false,
						"MES实名读取完整执行与生效水位的实际工厂权限；现有预览读取权不等于变更执行权"));
	}

	/**
	 * 输出完整字段快照，null原样保留，数量不进入double。
	 */
	private ObjectNode snapshot(Object... pairs) {
		var node = JSON.createObjectNode();
		for (int i = 0; i < pairs.length; i += 2) {
			node.set((String) pairs[i], JSON.valueToTree(pairs[i + 1]));
		}
		return node;
	}

	/**
	 * 原正式版本对直接来自已核验服务端来源。
	 */
	private PackageRef reference(MdmRouteEngineeringDTO.ProductionPackage source) {
		return new PackageRef(textId(source.getReleaseId()), source.getContentHash(), textId(source.getRouteId()),
				textId(source.getBomId()), source.getVersionNo());
	}

	private String processCode(JsonNode node) {
		return node == null ? null : node.path("processCode").asText();
	}

	private String raw(JsonNode node) {
		return node == null ? null : node.toString();
	}

	private boolean quantity(BigDecimal value) {
		return value != null && value.signum() >= 0;
	}

	private String textId(Long value) {
		return value == null ? null : value.toString();
	}

	/**
	 * 精确整数字符串防止JavaScript雪花ID精度丢失。
	 */
	private Long id(String value) {
		require(value != null && value.matches("[1-9][0-9]{0,18}"), "工程ID须为精确正整数字符串");
		try {
			return Long.valueOf(value);
		} catch (NumberFormatException failure) {
			throw new CheckedException("工程ID超出长整型范围");
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}

	/**
	 * 本地事实索引仅用于本次返回，不持久化、不充当提供方原件。
	 */
	private record FactIndex(Map<String, List<ExecutionFact>> bound, List<ExecutionFact> unbound) {
	}
}
