package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.common.process.ProcessCompositionValidator;
import com.kitex.mom.mes.dto.MesProcessCompositionDTO;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.util.MesFrozenExecutionGraph;

import java.util.*;

/**
 * 组合定义到实际领域对象的受控映射。
 */
public final class MesProcessCompositionModel {

	private MesProcessCompositionModel() {
	}

	public static void validate(MesProcessCompositionDTO.Definition definition) {
		var violations = ProcessCompositionValidator.validate(
				MesProcessCompositionSchema.definition(definition), MesProcessCompositionSchema.schema());
		if (!violations.isEmpty()) {
			var first = violations.get(0);
			throw new CheckedException(first.path() + "：" + first.message());
		}
	}

	public static List<MesProcessCompositionDTO.Node> bind(MesProcessCompositionDTO.Definition definition,
														   List<MesTaskEntity> tasks, List<MesProduceTaskEntity> operations,
														   List<MesTaskArrangeEntity> arrangements) {
		return bind(definition, tasks, operations, arrangements, Map.of());
	}

	/**
	 * 按实际派工展开一对多实例；模板职责仍唯一，数量统计继续由原工序持有。
	 * @param allocationKeys 实际派工ID到原稳定分配行的映射；旧调用可为空映射
	 */
	public static List<MesProcessCompositionDTO.Node> bind(MesProcessCompositionDTO.Definition definition,
			List<MesTaskEntity> tasks, List<MesProduceTaskEntity> operations,
			List<MesTaskArrangeEntity> arrangements, Map<Long, String> allocationKeys) {
		validate(definition);
		require(tasks != null && !tasks.isEmpty() && operations != null && arrangements != null && allocationKeys != null,
				"过程运行缺少正式工单、冻结工序或实际派工");
		var taskByCode = new LinkedHashMap<String, MesTaskEntity>();
		var operationByCode = new LinkedHashMap<String, MesProduceTaskEntity>();
		var assignmentByOperation = new HashMap<String, List<MesTaskArrangeEntity>>();
		Set<Long> assignmentIds = new HashSet<>();
		Set<String> assignmentCodes = new HashSet<>();
		var first = tasks.get(0);
		for (var task : tasks) {
			require(task != null && task.getId() != null && task.getCode() != null
					&& Objects.equals(first.getTenantId(), task.getTenantId())
					&& Objects.equals(first.getFactoryCode(), task.getFactoryCode())
					&& taskByCode.putIfAbsent(task.getCode(), task) == null, "工单归属缺失、重复或跨组织");
		}
		for (var task : tasks) {
			var members = operations.stream().filter(row -> Objects.equals(task.getCode(), row.getTaskCode())).toList();
			MesFrozenExecutionGraph.of(members);
		}
		for (var operation : operations) {
			require(operation.getId() != null && taskByCode.containsKey(operation.getTaskCode())
							&& Objects.equals(first.getTenantId(), operation.getTenantId())
							&& Objects.equals(first.getFactoryCode(), operation.getFactoryCode())
							&& operationByCode.putIfAbsent(operation.getCode(), operation) == null,
					"工序映射跨组织、跨工单或重复");
		}
		for (var arrangement : arrangements) {
			var operation = operationByCode.get(arrangement.getProduceTaskCode());
			require(operation != null && arrangement.getId() != null && arrangement.getCode() != null
							&& assignmentIds.add(arrangement.getId()) && assignmentCodes.add(arrangement.getCode())
							&& Objects.equals(operation.getTaskCode(), arrangement.getTaskCode())
							&& Objects.equals(first.getTenantId(), arrangement.getTenantId())
							&& Objects.equals(first.getFactoryCode(), arrangement.getFactoryCode()),
					"实际派工身份重复或不属于同一原工序/工单/组织");
			MesFrozenExecutionGraph.assertArrangement(operation, arrangement);
			assignmentByOperation.computeIfAbsent(operation.getCode(), ignored -> new ArrayList<>()).add(arrangement);
		}
		require(assignmentByOperation.size() == operationByCode.size(), "过程运行未覆盖完整冻结工序");
		var nodes = new ArrayList<MesProcessCompositionDTO.Node>();
		var parents = new HashMap<String, String>();
		String orderId = null;
		for (var level : definition.getLevels()) {
			String role = level.getBindings().get(0);
			if ("ORDER".equals(role)) {
				String order = first.getProductionOrder();
				require(order != null && !order.isBlank()
								&& tasks.stream().allMatch(task -> order.equals(task.getProductionOrder())),
						"四级过程须来自同一真实生产订单，缺少或混合来源不能运行");
				var node = node(level, order, null);
				nodes.add(node);
				orderId = node.getId();
			} else if ("TASK".equals(role)) {
				for (var task : tasks) {
					var node = node(level, task.getCode(), orderId);
					node.setTaskId(task.getId().toString());
					nodes.add(node);
					parents.put("TASK:" + task.getCode(), node.getId());
				}
			} else if ("OPERATION".equals(role)) {
				for (var operation : operations) {
					var members = assignmentByOperation.get(operation.getCode());
					if (level.getBindings().contains("ASSIGNMENT")) {
						// 合并的是显示职责，不合并真实派工；每条分配以其真实派工身份展开。
						for (var arrangement : members) {
							var node = node(level, arrangement.getCode(), parents.get("TASK:" + operation.getTaskCode()));
							node.setTaskId(taskByCode.get(operation.getTaskCode()).getId().toString());
							node.setProduceTaskId(operation.getId().toString());
							node.setRouteNodeCode(operation.getSourceRouteNodeCode());
							assignment(node, arrangement, allocationKeys);
							nodes.add(node);
						}
					} else {
						var node = node(level, operation.getCode(), parents.get("TASK:" + operation.getTaskCode()));
						node.setTaskId(taskByCode.get(operation.getTaskCode()).getId().toString());
						node.setProduceTaskId(operation.getId().toString());
						node.setRouteNodeCode(operation.getSourceRouteNodeCode());
						nodes.add(node);
						parents.put("OPERATION:" + operation.getCode(), node.getId());
					}
				}
			} else {
				for (var arrangement : arrangements) {
					var operation = operationByCode.get(arrangement.getProduceTaskCode());
					var node = node(level, arrangement.getCode(), parents.get("OPERATION:" + operation.getCode()));
					node.setTaskId(taskByCode.get(operation.getTaskCode()).getId().toString());
					node.setProduceTaskId(operation.getId().toString());
					assignment(node, arrangement, allocationKeys);
					node.setRouteNodeCode(operation.getSourceRouteNodeCode());
					nodes.add(node);
				}
			}
		}
		return List.copyOf(nodes);
	}

	/** 为显示节点保留真实派工及配额原件，不将同层多个实例重复计入父产量。 */
	private static void assignment(MesProcessCompositionDTO.Node node, MesTaskArrangeEntity arrangement,
			Map<Long, String> allocationKeys) {
		node.setArrangeId(arrangement.getId().toString());
		node.setAllocationKey(allocationKeys.get(arrangement.getId()));
		node.setAllocatedQuantity(arrangement.getNoticeNum() == null ? null
				: arrangement.getNoticeNum().stripTrailingZeros().toPlainString());
	}

	public static List<MesProcessCompositionDTO.Progress> progress(List<MesTaskEntity> tasks,
																   List<MesProduceTaskEntity> operations) {
		var result = new ArrayList<MesProcessCompositionDTO.Progress>();
		for (var task : tasks) {
			var members = operations.stream().filter(row -> Objects.equals(task.getCode(), row.getTaskCode())).toList();
			MesFrozenExecutionGraph.of(members);
			var terminals = members.stream().filter(row -> Boolean.TRUE.equals(row.getLastRoute())).toList();
			require(terminals.size() == 1, "工单缺少唯一实际末工序，禁止猜测完工量");
			var progress = new MesProcessCompositionDTO.Progress();
			progress.setTaskCode(task.getCode());
			progress.setMaterialCode(task.getMatCode());
			progress.setPlannedQuantity(task.getNoticeNum());
			progress.setCompletedQuantity(terminals.get(0).getCompleteNum());
			progress.setStatus(task.getStatus());
			result.add(progress);
		}
		return List.copyOf(result);
	}

	private static MesProcessCompositionDTO.Node node(MesProcessCompositionDTO.Level level, String source,
													  String parentId) {
		var node = new MesProcessCompositionDTO.Node();
		node.setId(level.getKey() + ":" + source);
		node.setParentId(parentId);
		node.setLevelKey(level.getKey());
		node.setLabel(level.getLabel());
		node.setBindings(List.copyOf(level.getBindings()));
		node.setSourceCode(source);
		return node;
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
