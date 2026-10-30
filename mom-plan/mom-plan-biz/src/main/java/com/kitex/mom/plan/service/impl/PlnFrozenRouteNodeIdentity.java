package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesApsLocalScheduleDTO;
import com.kitex.mom.plan.entity.PlnSchedulingOperationEntity;

import java.util.*;

/**
 * 局部重排只使用原正式排程冻结身份，不从今日路线补造历史节点。
 */
final class PlnFrozenRouteNodeIdentity {
    private PlnFrozenRouteNodeIdentity() {
    }

    static String key(PlnSchedulingOperationEntity operation) {
        return StrUtil.isNotBlank(operation.getSourceRouteNodeCode())
                ? operation.getSourceRouteNodeCode() : operation.getProcessCode();
    }

    static String key(MesApsLocalScheduleDTO.OperationState state) {
        return StrUtil.isNotBlank(state.getSourceRouteNodeCode()) ? state.getSourceRouteNodeCode() : state.getRouteCode();
    }

    static String predecessors(PlnSchedulingOperationEntity operation) {
        return StrUtil.isNotBlank(operation.getSourceRouteNodeCode())
                ? operation.getPredecessorNodeCodes() : operation.getPredecessorCodes();
    }

    /**
     * 全组使用同一身份合同；任何重复、缺失或新旧混合均拒绝，不取首条。
     */
    static Map<String, PlnSchedulingOperationEntity> index(List<PlnSchedulingOperationEntity> operations) {
        Map<String, PlnSchedulingOperationEntity> result = new LinkedHashMap<>();
        Boolean nodeMode = null;
        Set<Long> ids = new HashSet<>();
        for (var operation : operations) {
            check(operation != null && StrUtil.isNotBlank(operation.getProcessCode()) && operation.getId() != null
                    && ids.add(operation.getId()), "冻结APS工序缺失真实身份或重复主键");
            boolean node = StrUtil.isNotBlank(operation.getSourceRouteNodeCode());
            check(nodeMode == null || nodeMode == node, "冻结APS节点合同混合不完整，禁止猜测身份");
            nodeMode = node;
            check(!node || operation.getPredecessorNodeCodes() != null, "冻结APS节点前序缺失，禁止以主工序补造");
            check(result.putIfAbsent(key(operation), operation) == null, "冻结APS节点或旧主工序重复，禁止取首条");
        }
        return result;
    }

    static Map<String, MesApsLocalScheduleDTO.OperationState> states(MesApsLocalScheduleDTO.StateBundle bundle) {
        Map<String, MesApsLocalScheduleDTO.OperationState> result = new LinkedHashMap<>();
        Boolean nodeMode = null;
        Set<Long> ids = new HashSet<>();
        for (var state : Optional.ofNullable(bundle.getOperations()).orElse(List.of())) {
            check(state != null && StrUtil.isNotBlank(state.getRouteCode()) && state.getProduceTaskId() != null
                            && StrUtil.isNotBlank(state.getProduceTaskCode()) && ids.add(state.getProduceTaskId()),
                    "MES工序缺少真实任务身份或重复主键");
            boolean node = StrUtil.isNotBlank(state.getSourceRouteNodeCode());
            check(nodeMode == null || nodeMode == node, "MES节点合同混合不完整");
            nodeMode = node;
            check(result.putIfAbsent(key(state), state) == null, "MES节点或旧主工序重复，禁止取首条");
        }
        return result;
    }

    /**
     * 核对两侧完整集合；旧APS仅允许在原MES主工序唯一时桥接。
     */
    static Map<String, MesApsLocalScheduleDTO.OperationState> correspondence(
            List<PlnSchedulingOperationEntity> operations, MesApsLocalScheduleDTO.StateBundle bundle) {
        var plans = index(operations);
        if (!Boolean.TRUE.equals(bundle.getMesTaskPresent())) {
            check(bundle.getOperations() == null || bundle.getOperations().isEmpty(), "MES无工单标记与实际工序不一致");
            return Map.of();
        }
        var states = states(bundle);
        if (!operations.isEmpty() && StrUtil.isBlank(operations.get(0).getSourceRouteNodeCode())) {
            // 旧MES原冻结可能已有节点，但不能据此回填旧APS或改变其原审批合同。
            Map<String, MesApsLocalScheduleDTO.OperationState> legacy = new LinkedHashMap<>();
            for (var state : states.values()) {
                check(legacy.putIfAbsent(state.getRouteCode(), state) == null, "旧冻结两侧主工序不能唯一桥接");
            }
            states = legacy;
        }
        check(plans.keySet().equals(states.keySet()), "冻结APS与MES存在遗漏、额外或不一致节点，必须全量一一对应");
        for (var operation : operations) {
            var state = states.get(key(operation));
            check(Objects.equals(operation.getProcessCode(), state.getRouteCode())
                            && (StrUtil.isBlank(operation.getSourceRouteNodeCode())
                            || Objects.equals(operation.getSourceRouteNodeCode(), state.getSourceRouteNodeCode()))
                            && Objects.equals(operation.getOperationSeq(), state.getRouteOrder()),
                    "冻结APS与MES节点、主工序或序号不一致");
        }
        return states;
    }

    static MesApsLocalScheduleDTO.OperationState state(Map<String, MesApsLocalScheduleDTO.OperationState> states, String key) {
        var exact = states.get(key);
        if (exact != null) return exact;
        check(states.values().stream().map(MesApsLocalScheduleDTO.OperationState::getRouteCode).distinct().count() == states.size(),
                "旧MES主工序不能唯一桥接");
        var matches = states.values().stream().filter(row -> Objects.equals(key, row.getRouteCode())).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    static List<String> selection(List<PlnSchedulingOperationEntity> operations,
                                  Collection<String> processCodes, Collection<String> nodeCodes) {
        var index = index(operations);
        if (nodeCodes != null) {
            check(!nodeCodes.isEmpty() && new HashSet<>(nodeCodes).size() == nodeCodes.size()
                    && nodeCodes.stream().allMatch(code -> StrUtil.isNotBlank(code) && index.containsKey(code)
                    && Objects.equals(code, index.get(code).getSourceRouteNodeCode())), "指定冻结节点范围无效或重复");
            if (processCodes != null && !processCodes.isEmpty()) {
                check(new HashSet<>(processCodes).equals(nodeCodes.stream().map(code -> index.get(code).getProcessCode())
                        .collect(java.util.stream.Collectors.toSet())), "节点与主工序选择不一致");
            }
            return new ArrayList<>(nodeCodes);
        }
        List<String> result = new ArrayList<>();
        for (String process : Optional.ofNullable(processCodes).orElse(List.of())) {
            var matches = operations.stream().filter(row -> Objects.equals(process, row.getProcessCode())).toList();
            check(matches.size() == 1, "旧主工序选择无法唯一对应原冻结工序，请明确选择节点");
            result.add(key(matches.get(0)));
        }
        return result;
    }

    /**
     * 替换/回退只改变计划属性，不能换原组织、路线、节点、前序或来源计划。
     */
    static void sameIdentity(PlnSchedulingOperationEntity source, PlnSchedulingOperationEntity target) {
        check(source != null && target != null && Objects.equals(source.getSourceRouteNodeCode(), target.getSourceRouteNodeCode())
                        && Objects.equals(source.getProcessCode(), target.getProcessCode())
                        && Objects.equals(source.getRouteCode(), target.getRouteCode())
                        && Objects.equals(source.getRouteWorkingId(), target.getRouteWorkingId())
                        && Objects.equals(source.getPredecessorCodes(), target.getPredecessorCodes())
                        && Objects.equals(source.getPredecessorNodeCodes(), target.getPredecessorNodeCodes())
                        && Objects.equals(source.getOperationSeq(), target.getOperationSeq())
                        && Objects.equals(source.getIssueId(), target.getIssueId())
                        && Objects.equals(source.getFactoryCode(), target.getFactoryCode())
                        && Objects.equals(source.getTenantId(), target.getTenantId()),
                "候选/回退工序与原冻结节点、路线或组织不一致，禁止跨节点覆盖");
    }

    static void replacementSet(List<PlnSchedulingOperationEntity> originals, List<PlnSchedulingOperationEntity> candidates) {
        var before = index(originals);
        var after = index(candidates);
        check(before.keySet().equals(after.keySet()), "候选范围与原完整冻结图不一致");
        for (var row : candidates) sameIdentity(row, before.get(key(row)));
    }

    static String lineKey(Long originalId, String node, String process,
                          Map<String, PlnSchedulingOperationEntity> originals) {
        var matches = originals.values().stream().filter(row -> Objects.equals(originalId, row.getId())).toList();
        check(originalId != null && matches.size() == 1, "重排明细不能对应真实原工序主键");
        var original = matches.get(0);
        check(Objects.equals(node, original.getSourceRouteNodeCode()) && Objects.equals(process, original.getProcessCode()),
                "重排明细节点或主工序与真实原工序不一致");
        return key(original);
    }

    /**
     * 从真实直接受影响节点求后序闭包；先验证原冻结图无悬空、自环和循环。
     */
    static Set<String> closure(List<PlnSchedulingOperationEntity> operations, Collection<String> direct) {
        var index = index(operations);
        Set<String> result = new LinkedHashSet<>(direct);
        check(index.keySet().containsAll(result), "影响范围包含未知原冻结节点");
        for (var operation : operations) {
            for (var parent : split(predecessors(operation))) {
                check(index.containsKey(parent) && !Objects.equals(parent, key(operation)), "冻结前序缺失或自环");
            }
        }
        Set<String> ordered = new HashSet<>();
        while (ordered.size() < index.size()) {
            int before = ordered.size();
            for (var operation : operations) {
                if (ordered.containsAll(split(predecessors(operation)))) ordered.add(key(operation));
            }
            check(ordered.size() > before, "冻结节点图存在循环，禁止局部重排");
        }
        boolean changed;
        do {
            changed = false;
            for (var operation : operations) {
                if (split(predecessors(operation)).stream().anyMatch(result::contains))
                    changed |= result.add(key(operation));
            }
        } while (changed);
        return result;
    }

    static List<String> nodeCodes(List<PlnSchedulingOperationEntity> operations) {
        index(operations);
        return operations.isEmpty() || StrUtil.isBlank(operations.get(0).getSourceRouteNodeCode()) ? null
                : operations.stream().map(PlnSchedulingOperationEntity::getSourceRouteNodeCode).toList();
    }

    private static List<String> split(String value) {
        return Arrays.stream(StrUtil.blankToDefault(value, "").split("[,;]")).map(String::trim).filter(StrUtil::isNotBlank).toList();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
