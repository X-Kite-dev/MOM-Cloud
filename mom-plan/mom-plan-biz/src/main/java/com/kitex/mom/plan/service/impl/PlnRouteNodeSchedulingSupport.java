package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.dto.MdmProcessRouteDTO;
import com.kitex.mom.mdm.vo.MdmProcessRouteWorkingVO;
import com.kitex.mom.plan.dto.PlnOperationMaterialConstraintDTO;
import com.kitex.mom.plan.entity.PlnSchedulingOperationEntity;

import java.util.*;

/**
 * 仅供当前排程服务使用；节点身份与主工序能力始终分离。
 */
final class PlnRouteNodeSchedulingSupport {
    private PlnRouteNodeSchedulingSupport() {
    }

    static String nodeCode(Long workingId, String declaredCode) {
        if (workingId == null || workingId <= 0) throw new CheckedException("路线节点缺少真实关联ID");
        String expected = "RN-" + workingId;
        if (declaredCode != null && !expected.equals(declaredCode))
            throw new CheckedException("路线节点身份与真实关联ID不一致：" + declaredCode);
        return expected;
    }

    static MdmProcessRouteWorkingVO working(List<MdmProcessRouteWorkingVO> workings,
                                            String nodeCode, String processCode, Long workingId) {
        if (StrUtil.isBlank(processCode) || nodeCode != null && StrUtil.isBlank(nodeCode))
            throw new CheckedException("工序快照缺少合法节点或主工序身份");
        List<MdmProcessRouteWorkingVO> matches = workings.stream().filter(item -> nodeCode == null
                ? Objects.equals(processCode, item.getProcessCode())
                : Objects.equals(nodeCode, nodeCode(item.getId(), item.getRouteNodeCode()))).toList();
        if (matches.size() != 1) throw new CheckedException("路线工序[" + processCode + "]不能唯一对应原节点");
        MdmProcessRouteWorkingVO match = matches.get(0);
        if (!Objects.equals(processCode, match.getProcessCode())
                || workingId != null && !Objects.equals(workingId, match.getId()))
            throw new CheckedException("工序快照节点已删除、改绑或关联ID不一致");
        return match;
    }

    static Map<String, PlnSchedulingOperationEntity> operations(MdmProcessRouteDTO route,
                                                                List<MdmProcessRouteWorkingVO> workings, List<PlnSchedulingOperationEntity> operations) {
        if (operations.stream().anyMatch(Objects::isNull)) throw new CheckedException("工序排程快照存在空行");
        if (operations.stream().anyMatch(item -> item.getSourceRouteNodeCode() == null)
                && operations.stream().anyMatch(item -> item.getSourceRouteNodeCode() != null))
            throw new CheckedException("同一排程不能混用旧主工序与节点身份合同");
        Map<String, PlnSchedulingOperationEntity> result = new LinkedHashMap<>();
        for (PlnSchedulingOperationEntity operation : operations) {
            if (!Objects.equals(route.getRouteCode(), operation.getRouteCode())
                    || !Objects.equals(route.getId(), operation.getRouteId())
                    || !Objects.equals(route.getFactoryCode(), operation.getFactoryCode()))
                throw new CheckedException("工序排程引用的路线已变化");
            MdmProcessRouteWorkingVO working = working(workings, operation.getSourceRouteNodeCode(),
                    operation.getProcessCode(), operation.getRouteWorkingId());
            String key = nodeCode(working.getId(), working.getRouteNodeCode());
            boolean nodeContract = operation.getSourceRouteNodeCode() != null;
            if (nodeContract && (operation.getRouteWorkingId() == null || operation.getPredecessorNodeCodes() == null)
                    || !nodeContract && operation.getPredecessorNodeCodes() != null)
                throw new CheckedException("排程的节点身份与前序合同不完整");
            Set<String> expectedNodes = working.getPreRouteNodeCodes() == null ? Set.of()
                    : new LinkedHashSet<>(Arrays.asList(working.getPreRouteNodeCodes()));
            Set<String> expectedProcesses = working.getPreRouteCode() == null ? Set.of()
                    : new LinkedHashSet<>(Arrays.asList(working.getPreRouteCode()));
            if (nodeContract && !csv(operation.getPredecessorNodeCodes()).equals(expectedNodes)
                    || !csv(operation.getPredecessorCodes()).equals(expectedProcesses))
                throw new CheckedException("原排程的节点前序关系已变化，不能自动改写原冻结合同");
            if (result.putIfAbsent(key, operation) != null)
                throw new CheckedException("工序排程存在重复节点：" + key);
        }
        if (result.size() != workings.size()) throw new CheckedException("工序排程遗漏节点或路线节点数量已变化");
        return result;
    }

    private static Set<String> csv(String value) {
        if (value == null || value.isEmpty()) return Set.of();
        String[] items = value.split(",", -1);
        Set<String> result = new LinkedHashSet<>(Arrays.asList(items));
        if (result.size() != items.length || result.stream().anyMatch(item -> StrUtil.isBlank(item) || !item.equals(item.trim())))
            throw new CheckedException("排程前序身份含重复或空值");
        return result;
    }

    static String materialKey(PlnOperationMaterialConstraintDTO value) {
        return value.getRouteCode() + "|" + (value.getSourceRouteNodeCode() == null
                ? "P:" + value.getProcessCode() : "N:" + value.getSourceRouteNodeCode());
    }

    static Map<String, PlnOperationMaterialConstraintDTO> materials(String routeCode,
                                                                    List<MdmProcessRouteWorkingVO> workings, Iterable<PlnOperationMaterialConstraintDTO> values) {
        Map<String, PlnOperationMaterialConstraintDTO> result = new LinkedHashMap<>();
        for (PlnOperationMaterialConstraintDTO value : values) {
            if (!Objects.equals(routeCode, value.getRouteCode()))
                throw new CheckedException("工序投料快照引用的路线已变化");
            MdmProcessRouteWorkingVO working = working(workings, value.getSourceRouteNodeCode(),
                    value.getProcessCode(), null);
            String key = nodeCode(working.getId(), working.getRouteNodeCode());
            if (result.putIfAbsent(key, value) != null)
                throw new CheckedException("工序投料快照存在重复或混合合同节点：" + key);
        }
        return result;
    }
}
