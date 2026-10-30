package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MdmProcessRouteWorkingDTO;
import com.kitex.mom.mes.dto.MesApsOperationSnapshotDTO;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 下达时核完整APS与MES冻结图；节点与主工序分离，旧快照只能明确唯一桥接。
 */
final class MesApsRouteNodeMatcher {

    private MesApsRouteNodeMatcher() {
    }

    static Map<String, MesApsOperationSnapshotDTO> matchAll(List<MesApsOperationSnapshotDTO> operations,
                                                            List<MdmProcessRouteWorkingDTO> routes, String routeCode) {
        require(operations.size() == routes.size(), "APS与MES冻结工序数量不一致，禁止忽略额外或缺失工序");
        boolean nodeMode = operations.stream().anyMatch(row -> StringUtils.hasText(row.getSourceRouteNodeCode()));
        require(!nodeMode || operations.stream().allMatch(row -> validNode(row.getSourceRouteNodeCode())
                && row.getPredecessorNodeCodes() != null), "APS节点合同不完整或新旧身份混用，请重新排产");
        Map<String, MesApsOperationSnapshotDTO> byIdentity = new LinkedHashMap<>();
        for (MesApsOperationSnapshotDTO operation : operations) {
            require(StringUtils.hasText(operation.getProcessCode()), "APS冻结主工序不能为空");
            require(!StringUtils.hasText(operation.getRouteCode()) || Objects.equals(routeCode, operation.getRouteCode()),
                    "APS冻结路线与MES工单路线不一致");
            if (nodeMode) {
                require(Objects.equals(routeCode, operation.getRouteCode()) && operation.getRouteWorkingId() != null
                                && Objects.equals(operation.getSourceRouteNodeCode(), "RN-" + operation.getRouteWorkingId()),
                        "APS节点与原路线记录不一致");
            } else {
                require(operation.getSourceRouteNodeCode() == null && operation.getPredecessorNodeCodes() == null,
                        "旧APS合同不得携带不完整节点字段");
            }
            String key = nodeMode ? operation.getSourceRouteNodeCode() : operation.getProcessCode();
            require(byIdentity.putIfAbsent(key, operation) == null, "APS冻结节点重复；旧主工序合同必须唯一");
        }
        Map<String, List<MdmProcessRouteWorkingDTO>> byProcess = routes.stream()
                .collect(Collectors.groupingBy(MesApsRouteNodeMatcher::processCode));
        if (!nodeMode) require(byProcess.values().stream().allMatch(rows -> rows.size() == 1),
                "旧APS只能对应原MES冻结中的唯一主工序，不能按序号猜节点");
        Map<String, MesApsOperationSnapshotDTO> result = new LinkedHashMap<>();
        Set<String> consumed = new LinkedHashSet<>();
        for (MdmProcessRouteWorkingDTO route : routes) {
            String key = nodeMode ? route.getRouteNodeCode() : processCode(route);
            require(!nodeMode || validNode(key), "MES冻结路线缺少真实节点身份");
            MesApsOperationSnapshotDTO operation = byIdentity.get(key);
            require(operation != null && consumed.add(key) && Objects.equals(processCode(route), operation.getProcessCode()),
                    "MES路线节点未唯一匹配原APS主工序，禁止下达");
            if (nodeMode) {
                Set<String> predecessors = new LinkedHashSet<>();
                if (route.getPreRouteNodeCodes() != null) {
                    predecessors.addAll(Arrays.asList(route.getPreRouteNodeCodes()));
                    require(predecessors.size() == route.getPreRouteNodeCodes().length
                                    && predecessors.stream().allMatch(MesApsRouteNodeMatcher::validNode),
                            "MES冻结节点前序不合法");
                } else {
                    // 旧唯一工程图用于本次新下达，可在本次完整冻结图内投影；不查询今日MDM补历史。
                    for (String predecessor : codes(route.getPreRouteCode())) {
                        List<MdmProcessRouteWorkingDTO> matches = byProcess.getOrDefault(predecessor, List.of());
                        require(matches.size() == 1 && validNode(matches.get(0).getRouteNodeCode()),
                                "MES旧前序无法在本次冻结图内唯一对应节点");
                        predecessors.add(matches.get(0).getRouteNodeCode());
                    }
                }
                require(predecessors.equals(codes(operation.getPredecessorNodeCodes().split(",", -1))),
                        "MES与APS冻结节点前序不一致");
            }
            require(result.putIfAbsent(routeKey(route), operation) == null, "MES冻结节点重复");
        }
        require(consumed.size() == operations.size(), "APS存在未对应MES的额外冻结工序");
        return result;
    }

    static String routeKey(MdmProcessRouteWorkingDTO route) {
        return StringUtils.hasText(route.getRouteNodeCode()) ? "N:" + route.getRouteNodeCode() : "P:" + processCode(route);
    }

    private static String processCode(MdmProcessRouteWorkingDTO route) {
        String code = StringUtils.hasText(route.getProcessCode()) ? route.getProcessCode() : route.getRouteCode();
        require(StringUtils.hasText(code), "MES冻结主工序不能为空");
        return code;
    }

    private static Set<String> codes(String[] values) {
        if (values == null) return Set.of();
        return Arrays.stream(values).filter(StringUtils::hasText).map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static boolean validNode(String code) {
        return code != null && code.matches("RN-[1-9][0-9]*");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
