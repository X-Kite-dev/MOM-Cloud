package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    private final MesProductionOrderCapacityGuard capacityGuard;
    private final MesFormalPlanAllocationService contractAllocations;

    @Override
    public Boolean schedule(MesProductionOrderDTO query) {
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesProductionOrderEntity recordReceivedOrder(MesTaskReceiveDTO receive, MesTaskEntity task) {
        if (receive == null || task == null || StrUtil.isBlank(receive.getProductionOrder())) {
            throw new CheckedException("登记生产订单缺少MES接收事实");
        }
        Long tenantId = TenantContextHolder.getTenantId();
        String factoryCode = FactoryContextHolder.getFactoryCode();
        if (tenantId == null || tenantId <= 0 || StrUtil.isBlank(factoryCode)
                || !Objects.equals(factoryCode, receive.getFactoryCode())) {
            throw new CheckedException("登记生产订单必须处于明确的租户和工厂上下文");
        }
        if (receive.getProductionOrderQuantity() == null || receive.getProductionOrderQuantity().signum() <= 0
                || receive.getNoticeNum() == null || receive.getNoticeNum().signum() <= 0
                || receive.getNoticeNum().compareTo(receive.getProductionOrderQuantity()) > 0) {
            throw new CheckedException("登记生产订单的总量或本批接收数量无效");
        }
        contractAllocations.allocate(receive, task);
        baseMapper.lockReceivedOrder(String.join(":", "MES_RECEIVED_ORDER", tenantId.toString(),
                factoryCode, receive.getProductionOrder()));
        capacityGuard.verify(receive, task, tenantId, factoryCode);
        List<MesProductionOrderEntity> matches = list(new LambdaQueryWrapper<MesProductionOrderEntity>()
                .eq(MesProductionOrderEntity::getTenantId, tenantId)
                .eq(MesProductionOrderEntity::getFactoryCode, factoryCode)
                .eq(MesProductionOrderEntity::getOrderNo, receive.getProductionOrder())
                .last("LIMIT 2"));
        if (matches.size() > 1) {
            throw new CheckedException("当前租户工厂存在重复生产订单主记录，请先核查来源");
        }
        MesProductionOrderEntity existing = matches.isEmpty() ? null : matches.get(0);
        if (existing != null) {
            if (!Objects.equals(existing.getFormalPlanContractNo(), receive.getFormalPlanContractNo())
                    || !Objects.equals(existing.getFormalPlanContractVersion(), receive.getFormalPlanContractVersion())
                    || !Objects.equals(existing.getFormalPlanContractFingerprint(),
                    receive.getFormalPlanContractFingerprint())
                    || !Objects.equals(existing.getProductCode(), receive.getMatCode())
                    || !Objects.equals(existing.getFactoryCode(), receive.getFactoryCode())
                    || !sameFrozenSource(existing, receive)
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

    /**
     * 订单主记录可供不同拆批复用，但不能借同一订单号收养不同的来源或计划权威。
     */
    private boolean sameFrozenSource(MesProductionOrderEntity existing, MesTaskReceiveDTO receive) {
        return "PLAN".equals(existing.getSourceType())
                && Objects.equals(existing.getSourceNo(), receive.getFormalPlanContractNo())
                && Objects.equals(existing.getSourceSystem(), receive.getSourceSystem())
                && Objects.equals(existing.getSourcePlanNo(), receive.getSourcePlanNo())
                && Objects.equals(existing.getSourcePlanVersion(), receive.getSourcePlanVersion())
                && Objects.equals(existing.getPlanningPolicyId(), receive.getPlanningPolicyId())
                && Objects.equals(existing.getPlanningPolicyVersion(), receive.getPlanningPolicyVersion())
                && Objects.equals(existing.getPlanningPolicyFingerprint(), receive.getPlanningPolicyFingerprint())
                && Objects.equals(existing.getMrpAuthority(), receive.getMrpAuthority())
                && Objects.equals(existing.getErpProductionOrderNo(), receive.getErpProductionOrderNo());
    }
}
