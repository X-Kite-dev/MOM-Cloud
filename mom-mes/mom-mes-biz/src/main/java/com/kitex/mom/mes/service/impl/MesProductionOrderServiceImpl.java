package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesProductionOrderDTO;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.mes.entity.MesProductionOrderEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesProductionOrderMapper;
import com.kitex.mom.mes.service.MesProductionOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * <p>
 * 生产订单主表 服务实现类
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MesProductionOrderServiceImpl extends ServiceImpl<MesProductionOrderMapper, MesProductionOrderEntity> implements MesProductionOrderService {

    @Override
    public Boolean schedule(MesProductionOrderDTO query) {
        return null;
    }

    @Override
    public MesProductionOrderEntity recordReceivedOrder(MesTaskReceiveDTO receive, MesTaskEntity task) {
        if (receive == null || task == null || receive.getProductionOrder() == null) {
            throw new CheckedException("登记生产订单缺少MES接收事实");
        }
        Long tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null || tenantId <= 0 || receive.getFactoryCode() == null) {
            throw new CheckedException("登记生产订单必须处于明确的租户和工厂上下文");
        }
        baseMapper.lockReceivedOrder(String.join(":", "MES_RECEIVED_ORDER", tenantId.toString(),
                receive.getFactoryCode(), receive.getProductionOrder()));
        MesProductionOrderEntity existing = getOne(new LambdaQueryWrapper<MesProductionOrderEntity>()
                .eq(MesProductionOrderEntity::getOrderNo, receive.getProductionOrder())
                .last("LIMIT 1"), false);
        if (existing != null) {
            if (!Objects.equals(existing.getFormalPlanContractNo(), receive.getFormalPlanContractNo())
                    || !Objects.equals(existing.getFormalPlanContractVersion(), receive.getFormalPlanContractVersion())
                    || !Objects.equals(existing.getFormalPlanContractFingerprint(),
                    receive.getFormalPlanContractFingerprint())
                    || !Objects.equals(existing.getProductCode(), receive.getMatCode())
                    || !Objects.equals(existing.getFactoryCode(), receive.getFactoryCode())
                    || existing.getOrderQuantity() == null
                    || existing.getOrderQuantity().compareTo(receive.getProductionOrderQuantity()) != 0) {
                throw new CheckedException("生产订单号已存在，但正式合同或产品来源不一致");
            }
            return existing;
        }
        MesProductionOrderEntity order = new MesProductionOrderEntity()
                .setFormalPlanContractId(receive.getFormalPlanContractId())
                .setFormalPlanContractNo(receive.getFormalPlanContractNo())
                .setFormalPlanContractVersion(receive.getFormalPlanContractVersion())
                .setFormalPlanContractFingerprint(receive.getFormalPlanContractFingerprint())
                .setSourceSystem(receive.getSourceSystem()).setSourcePlanNo(receive.getSourcePlanNo())
                .setSourcePlanVersion(receive.getSourcePlanVersion())
                .setPlanningPolicyId(receive.getPlanningPolicyId())
                .setPlanningPolicyVersion(receive.getPlanningPolicyVersion())
                .setPlanningPolicyFingerprint(receive.getPlanningPolicyFingerprint())
                .setMrpAuthority(receive.getMrpAuthority())
                .setErpProductionOrderNo(receive.getErpProductionOrderNo())
                .setOrderNo(receive.getProductionOrder()).setOrderType("STANDARD")
                .setSourceType("PLAN").setSourceNo(receive.getFormalPlanContractNo())
                .setProductCode(receive.getMatCode()).setProductName(receive.getMatName())
                .setProductSpec(receive.getMatSpec()).setOrderQuantity(receive.getProductionOrderQuantity())
                .setFinishedQuantity(java.math.BigDecimal.ZERO)
                .setRemainingQuantity(receive.getProductionOrderQuantity())
                .setBomId(receive.getBomId() == null ? null : String.valueOf(receive.getBomId()))
                .setBomVersion(receive.getBomVersion()).setPlanStartDate(task.getPlanStartDate())
                .setPlanEndDate(task.getPlanEndDate()).setStatus("SCHEDULED")
                .setWorkshopCode(receive.getWorkshopCode()).setFactoryCode(receive.getFactoryCode());
        if (!save(order)) {
            throw new CheckedException("MES生产订单主记录保存失败");
        }
        return order;
    }
}
