package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesTaskIssueReceiptDTO;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.mes.enums.MesTaskStatusEnum;
import com.kitex.mom.mes.mapper.MesTaskIssueRecoveryMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 丢失响应后的原回执续接，只读历史接收事实，不用新总量重新解释旧订单。
 */
@Service
@RequiredArgsConstructor
public class MesTaskIssueRecoveryService {
    private final MesTaskIssueRecoveryMapper facts;
    private final MesTaskMapper requestLocks;
    private final MesActualFactoryAccessService factoryAccess;
    private final MesProductionOrderCapacityGuard transactions;

    private static boolean same(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.signum() > 0 && left.compareTo(right) == 0;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public MesTaskIssueReceiptDTO recover(List<MesTaskReceiveDTO> candidates) {
        require(candidates != null && !candidates.isEmpty() && candidates.size() <= 2
                && candidates.stream().allMatch(Objects::nonNull), "原回执恢复只接受当前及旧版最多两份候选载荷");
        var request = candidates.get(0);
        String factory = factoryAccess.require("write");
        Long tenant = TenantContextHolder.getTenantId();
        require(tenant != null && tenant > 0 && !TenantContextHolder.getTenantSkip()
                && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                && Objects.equals(factory, FactoryContextHolder.getFactoryCode())
                && Objects.equals(factory, request.getFactoryCode()), "原回执恢复必须处于实名实际租户工厂");
        var fingerprints = new HashSet<String>();
        for (var candidate : candidates) {
            require(StrUtil.isNotBlank(candidate.getSourceRequestId()) && candidate.getSourceRequestId().length() <= 100
                    && Objects.equals(request.getSourceRequestId(), candidate.getSourceRequestId())
                    && Objects.equals(factory, candidate.getFactoryCode())
                    && candidate.getSourceRequestFingerprint() != null
                    && candidate.getSourceRequestFingerprint().matches("[0-9a-f]{64}")
                    && fingerprints.add(candidate.getSourceRequestFingerprint()), "候选的原请求号、工厂或载荷指纹不一致/重复");
        }
        transactions.requireCurrentStatementSnapshot();
        require(Objects.equals(1, requestLocks.acquireApsBatchTransactionLock("PLAN_ISSUE:" + request.getSourceRequestId())),
                "未能锁定原接单请求");
        var matches = facts.findRequest(tenant, factory, request.getSourceRequestId());
        require(matches != null && matches.size() <= 1, "原接单请求事实缺失或重复，不能猜测回执");
        if (matches.isEmpty()) return null;
        var task = matches.get(0);
        require(task != null, "原接单工单事实残缺");
        var selected = candidates.stream().filter(candidate -> Objects.equals(candidate.getSourceRequestFingerprint(),
                task.getSourceRequestFingerprint())).toList();
        require(selected.size() == 1, "已观察原接单请求，但当前及旧版载荷均与原指纹不一致");
        var expected = selected.get(0);
        // 使用PLAN按原算法重建的完整指纹；HTTP日期格式可能丢失毫秒，不能在此重算后冒充原摘要。
        require(task != null && Objects.equals(tenant, task.getTenantId()) && Objects.equals(factory, task.getFactoryCode())
                        && Objects.equals(task.getSourceRequestId(), expected.getSourceRequestId())
                        && Objects.equals(task.getSourceRequestFingerprint(), expected.getSourceRequestFingerprint())
                        && Objects.equals(task.getPlanNo(), expected.getPlanNo()) && Objects.equals(task.getMatCode(), expected.getMatCode())
                        && same(task.getNoticeNum(), expected.getNoticeNum())
                        && Objects.equals(task.getProductionOrder(), expected.getProductionOrder())
                        && Objects.equals(task.getFormalPlanContractId(), expected.getFormalPlanContractId())
                        && Objects.equals(task.getFormalPlanContractNo(), expected.getFormalPlanContractNo())
                        && Objects.equals(task.getFormalPlanContractVersion(), expected.getFormalPlanContractVersion())
                        && Objects.equals(task.getFormalPlanContractFingerprint(), expected.getFormalPlanContractFingerprint())
                        && Objects.equals(task.getSourceSystem(), expected.getSourceSystem())
                        && Objects.equals(task.getSourcePlanNo(), expected.getSourcePlanNo())
                        && Objects.equals(task.getSourcePlanVersion(), expected.getSourcePlanVersion())
                        && Objects.equals(task.getPlanningPolicyId(), expected.getPlanningPolicyId())
                        && Objects.equals(task.getPlanningPolicyVersion(), expected.getPlanningPolicyVersion())
                        && Objects.equals(task.getPlanningPolicyFingerprint(), expected.getPlanningPolicyFingerprint())
                        && Objects.equals(task.getMrpAuthority(), expected.getMrpAuthority())
                        && Objects.equals(task.getProductionOrderSyncMode(), expected.getProductionOrderSyncMode())
                        && Objects.equals(task.getErpProductionOrderNo(), expected.getErpProductionOrderNo())
                        && Objects.equals(task.getApsTaskId(), expected.getApsTaskId())
                        && Objects.equals(task.getApsBatchId(), expected.getApsBatchId())
                        && Objects.equals(task.getApsBatchNo(), expected.getApsBatchNo())
                        && Objects.equals(task.getApsBatchType(), expected.getApsBatchType())
                        && Objects.equals(task.getApsBatchFingerprint(), expected.getApsBatchFingerprint())
                        && Objects.equals(task.getEngineeringReleaseId(), expected.getEngineeringReleaseId())
                        && Objects.equals(task.getEngineeringContentHash(), expected.getEngineeringContentHash()),
                "已观察原接单请求，但原载荷指纹或冻结来源不一致");
        require(task.getId() != null && task.getId() > 0 && StrUtil.isNotBlank(task.getCode()) && task.getCreateTime() != null
                        && "0".equals(task.getDelFlag()) && Arrays.stream(MesTaskStatusEnum.values())
                        .anyMatch(status -> status != MesTaskStatusEnum.CLOSE && status.name().equals(task.getStatus())),
                "原接收工单已关闭、删除或回执证据残缺，请先核对原撤回结果");
        var orders = facts.findOrder(tenant, factory, expected.getProductionOrder());
        require(orders != null && orders.size() == 1, "原生产订单冻结总量无法唯一证明");
        var order = orders.get(0);
        require(order != null && "0".equals(order.getDelFlag()) && Objects.equals(tenant, order.getTenantId())
                        && Objects.equals(factory, order.getFactoryCode()) && Objects.equals(order.getOrderNo(), expected.getProductionOrder())
                        && same(order.getOrderQuantity(), expected.getProductionOrderQuantity())
                        && "PLAN".equals(order.getSourceType()) && Objects.equals(order.getSourceNo(), expected.getFormalPlanContractNo())
                        && Objects.equals(order.getProductCode(), expected.getMatCode())
                        && Objects.equals(order.getFormalPlanContractNo(), expected.getFormalPlanContractNo())
                        && Objects.equals(order.getFormalPlanContractVersion(), expected.getFormalPlanContractVersion())
                        && Objects.equals(order.getFormalPlanContractFingerprint(), expected.getFormalPlanContractFingerprint())
                        && Objects.equals(order.getSourceSystem(), expected.getSourceSystem())
                        && Objects.equals(order.getSourcePlanNo(), expected.getSourcePlanNo())
                        && Objects.equals(order.getSourcePlanVersion(), expected.getSourcePlanVersion())
                        && Objects.equals(order.getPlanningPolicyId(), expected.getPlanningPolicyId())
                        && Objects.equals(order.getPlanningPolicyVersion(), expected.getPlanningPolicyVersion())
                        && Objects.equals(order.getPlanningPolicyFingerprint(), expected.getPlanningPolicyFingerprint())
                        && Objects.equals(order.getMrpAuthority(), expected.getMrpAuthority())
                        && Objects.equals(order.getErpProductionOrderNo(), expected.getErpProductionOrderNo()),
                "原生产订单的冻结数量或来源不一致，禁止以新版总量改写");
        return new MesTaskIssueReceiptDTO().setRequestId(task.getSourceRequestId()).setRequestFingerprint(task.getSourceRequestFingerprint())
                .setTaskId(task.getId()).setTaskCode(task.getCode()).setPlanNo(task.getPlanNo()).setAcceptedTime(task.getCreateTime()).setReplayed(true);
    }
}
