package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryPlanningPolicyService;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.plan.dto.PlnFormalPlanContractDTO;
import com.kitex.mom.plan.feign.RemotePlanFormalContractService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * MES 接单前的正式计划合同二次核验器。
 *
 * <p>PLAN 已经执行过发布校验，但 MES 不能把跨服务请求本身当成事实。这里重新读取 MDM 当前
 * 工厂策略和 PLAN 已核验合同，阻止错工厂、旧策略、旧合同或伪造 ERP 订单号进入生产工单。</p>
 */
@Component
@RequiredArgsConstructor
public class MesFormalPlanReceiptVerifier {

    private final RemoteMdmFactoryPlanningPolicyService planningPolicyService;
    private final RemotePlanFormalContractService formalContractService;

    /** 校验成功才允许 MesTaskService 创建或幂等返回工单。 */
    public void verify(MesTaskReceiveDTO receive) {
        require(receive != null && StrUtil.isNotBlank(receive.getFactoryCode()),
                "MES接单缺少工厂编码");
        String requestFactory = receive.getFactoryCode().trim();
        String contextFactory = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(contextFactory) && Objects.equals(contextFactory, requestFactory),
                "MES接单工厂与请求上下文不一致");
        require(receive.getPlanningPolicyId() != null && receive.getPlanningPolicyId() > 0
                        && receive.getPlanningPolicyVersion() != null && receive.getPlanningPolicyVersion() > 0
                        && isFingerprint(receive.getPlanningPolicyFingerprint())
                        && receive.getFormalPlanContractId() != null && receive.getFormalPlanContractId() > 0
                        && StrUtil.isNotBlank(receive.getFormalPlanContractNo())
                        && receive.getFormalPlanContractVersion() != null
                        && receive.getFormalPlanContractVersion() > 0
                        && isFingerprint(receive.getFormalPlanContractFingerprint()),
                "MES接单缺少完整的计划权威或正式合同证据");

        R<MdmFactoryPlanningPolicyDTO> policyResponse = planningPolicyService.active(requestFactory);
        MdmFactoryPlanningPolicyDTO policy = policyResponse == null ? null : policyResponse.getData();
        require(policyResponse != null && policyResponse.isOk() && policy != null,
                "MES无法读取当前工厂计划权威，已停止接单");
        require(Objects.equals(requestFactory, policy.getFactoryCode())
                        && Objects.equals(String.valueOf(receive.getPlanningPolicyId()), policy.getId())
                        && Objects.equals(receive.getPlanningPolicyVersion(), policy.getVersionNo())
                        && Objects.equals(receive.getPlanningPolicyFingerprint(), policy.getContentFingerprint())
                        && Objects.equals(receive.getMrpAuthority(), policy.getMrpAuthority())
                        && Objects.equals(receive.getProductionOrderSyncMode(), policy.getProductionOrderSyncMode()),
                "MES接单携带的工厂计划权威已失效");

        R<PlnFormalPlanContractDTO> contractResponse = formalContractService.verified(
                receive.getFormalPlanContractNo(), receive.getFormalPlanContractVersion());
        PlnFormalPlanContractDTO contract = contractResponse == null ? null : contractResponse.getData();
        require(contractResponse != null && contractResponse.isOk() && contract != null,
                "MES无法读取已核验正式计划合同，已停止接单");
        require(Objects.equals(contract.getSourceContentFingerprint(), receive.getFormalPlanContractFingerprint())
                        && Objects.equals(contract.getPlanningPolicyId(), policy.getId())
                        && Objects.equals(contract.getPlanningPolicyVersion(), policy.getVersionNo())
                        && Objects.equals(contract.getPlanningPolicyFingerprint(), policy.getContentFingerprint())
                        && Objects.equals(contract.getSourceSystemCode(), receive.getSourceSystem())
                        && Objects.equals(contract.getSourcePlanNo(), receive.getSourcePlanNo())
                        && Objects.equals(contract.getSourcePlanVersion(), receive.getSourcePlanVersion()),
                "MES接单的正式合同来源或策略摘要不一致");
        PlnFormalPlanContractDTO.Line line = contract.getLines().stream()
                .filter(item -> Objects.equals(String.valueOf(receive.getFormalPlanContractId()), item.getId()))
                .filter(item -> "MAKE".equals(item.getSupplyType()))
                .findFirst().orElseThrow(() -> new CheckedException("MES接单引用的正式合同自制行不存在"));
        require(Objects.equals(line.getProductCode(), receive.getMatCode())
                        && receive.getNoticeNum() != null && receive.getNoticeNum().signum() > 0
                        && receive.getProductionOrderQuantity() != null
                        && receive.getProductionOrderQuantity().signum() > 0
                        && line.getPlannedQuantity() != null
                        && receive.getNoticeNum().compareTo(receive.getProductionOrderQuantity()) <= 0
                        && receive.getProductionOrderQuantity().compareTo(line.getPlannedQuantity()) <= 0,
                "MES工单产品或数量超出正式计划合同");
        if ("ERP_ORDER_REQUIRED".equals(policy.getProductionOrderSyncMode())) {
            require(StrUtil.isNotBlank(line.getErpProductionOrderNo())
                            && Objects.equals(line.getErpProductionOrderNo(), receive.getErpProductionOrderNo())
                            && Objects.equals(line.getErpProductionOrderNo(), receive.getProductionOrder()),
                    "ERP订单前置模式必须携带合同核验通过的ERP生产订单号");
        } else {
            require(StrUtil.isNotBlank(receive.getProductionOrder()),
                    "MOM订单执行模式必须生成稳定的本地生产订单号");
        }
    }

    private static boolean isFingerprint(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }
}
