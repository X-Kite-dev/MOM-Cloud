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
        validate(definition);
        require(tasks != null && !tasks.isEmpty() && operations != null && arrangements != null,
                "过程运行缺少正式工单、冻结工序或实际派工");
        var taskByCode = new LinkedHashMap<String, MesTaskEntity>();
        var operationByCode = new LinkedHashMap<String, MesProduceTaskEntity>();
        var assignmentByOperation = new HashMap<String, MesTaskArrangeEntity>();
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
                            && assignmentByOperation.putIfAbsent(operation.getCode(), arrangement) == null,
                    "当前组合运行要求每道冻结工序唯一实际派工");
            MesFrozenExecutionGraph.assertArrangement(operation, arrangement);
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
                    var arrangement = assignmentByOperation.get(operation.getCode());
                    var node = node(level, operation.getCode(), parents.get("TASK:" + operation.getTaskCode()));
                    node.setTaskId(taskByCode.get(operation.getTaskCode()).getId().toString());
                    node.setProduceTaskId(operation.getId().toString());
                    node.setRouteNodeCode(operation.getSourceRouteNodeCode());
                    if (level.getBindings().contains("ASSIGNMENT")) {
                        node.setArrangeId(arrangement.getId().toString());
                        node.setSourceCode(arrangement.getCode());
                    }
                    nodes.add(node);
                    parents.put("OPERATION:" + operation.getCode(), node.getId());
                }
            } else {
                for (var arrangement : arrangements) {
                    var operation = operationByCode.get(arrangement.getProduceTaskCode());
                    var node = node(level, arrangement.getCode(), parents.get("OPERATION:" + operation.getCode()));
                    node.setTaskId(taskByCode.get(operation.getTaskCode()).getId().toString());
                    node.setProduceTaskId(operation.getId().toString());
                    node.setArrangeId(arrangement.getId().toString());
                    node.setRouteNodeCode(operation.getSourceRouteNodeCode());
                    nodes.add(node);
                }
            }
        }
        return List.copyOf(nodes);
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
