package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.dto.WmsSalesStockReservationCommand;
import com.kitex.mom.wms.entity.WmsDeliverGoodsPlanBaseEntity;
import com.kitex.mom.wms.entity.WmsReservationMovementPolicyEntity;
import com.kitex.mom.wms.enums.DeliverStateEnum;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.WmsReservationMovementPolicyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WmsSalesReservationMovementPolicyTest {
    private final WmsLiveStockMapper stocks = mock(WmsLiveStockMapper.class);
    private final WmsDeliverGoodsPlanBaseMapper plans = mock(WmsDeliverGoodsPlanBaseMapper.class);
    private final WmsDeliverGoodsDetailsMapper deliveryDetails = mock(WmsDeliverGoodsDetailsMapper.class);
    private final WmsSalesStockReservationOperationMapper operations =
            mock(WmsSalesStockReservationOperationMapper.class);
    private final WmsReservationMovementPolicyMapper policies = mock(WmsReservationMovementPolicyMapper.class);

    @BeforeEach
    void setScope() {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
    }

    @AfterEach
    void clearScope() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void disabledSalesMovementRejectsNewReservationBeforeStockSelection() {
        WmsDeliverGoodsPlanBaseEntity plan = new WmsDeliverGoodsPlanBaseEntity();
        plan.setId(20L);
        plan.setFactoryCode("F1");
        plan.setDeliverPlanCode("P-20");
        plan.setProductCode("FG-1");
        plan.setDeliverNum(BigDecimal.TEN);
        plan.setDeliveredNum(BigDecimal.ZERO);
        plan.setDeliverState(DeliverStateEnum.NOT_START.getCode());
        when(plans.selectOne(any(Wrapper.class), eq(false))).thenReturn(plan);
        when(deliveryDetails.selectList(any(Wrapper.class))).thenReturn(List.of());
        WmsReservationMovementPolicyEntity policy = new WmsReservationMovementPolicyEntity()
                .setMovementType("sales")
                .setReservationAllowed(false)
                .setStatus(true)
                .setFactoryCode("F1");
        policy.setTenantId(1L);
        when(policies.selectList(any(Wrapper.class))).thenReturn(List.of(policy));
        WmsSalesStockReservationServiceImpl service = new WmsSalesStockReservationServiceImpl(
                mock(WmsSalesStockReservationMapper.class), mock(WmsSalesStockReservationDetailMapper.class),
                operations, plans, deliveryDetails, mock(WmsDeliverGoodsBaseMapper.class), stocks,
                mock(WmsMaterialReservationDetailMapper.class), null,
                new WmsReservationMovementPolicyService(policies));
        WmsSalesStockReservationCommand command = new WmsSalesStockReservationCommand();
        command.setDeliverPlanId(20L);
        command.setRequestId("S-20");
        command.setReason("为订单备货");

        CheckedException failure = assertThrows(CheckedException.class,
                () -> service.reserve(command));

        assertEquals("当前工厂的[sales]作业类型不允许新建库存预留", failure.getMessage());
        verify(stocks, never()).selectList(any(Wrapper.class));
    }

    @Test
    void skippedTenantScopeCannotReadOrReserveSalesStock() {
        WmsDeliverGoodsPlanBaseEntity plan = new WmsDeliverGoodsPlanBaseEntity();
        plan.setId(20L);
        plan.setFactoryCode("F1");
        plan.setDeliverPlanCode("P-20");
        plan.setProductCode("FG-1");
        plan.setDeliverNum(BigDecimal.TEN);
        plan.setDeliveredNum(BigDecimal.ZERO);
        plan.setDeliverState(DeliverStateEnum.NOT_START.getCode());
        when(plans.selectOne(any(Wrapper.class), eq(false))).thenReturn(plan);
        WmsSalesStockReservationServiceImpl service = new WmsSalesStockReservationServiceImpl(
                mock(WmsSalesStockReservationMapper.class), mock(WmsSalesStockReservationDetailMapper.class),
                operations, plans, deliveryDetails, mock(WmsDeliverGoodsBaseMapper.class), stocks,
                mock(WmsMaterialReservationDetailMapper.class), null,
                new WmsReservationMovementPolicyService(policies));
        WmsSalesStockReservationCommand command = new WmsSalesStockReservationCommand();
        command.setDeliverPlanId(20L);
        command.setRequestId("S-20");
        command.setReason("为订单备货");
        TenantContextHolder.setTenantSkip();

        CheckedException readFailure = assertThrows(CheckedException.class, () -> service.snapshot(20L));
        assertEquals("销售预留必须具有明确的租户和工厂上下文", readFailure.getMessage());
        CheckedException failure = assertThrows(CheckedException.class, () -> service.reserve(command));
        assertEquals("销售预留必须具有明确的租户和工厂上下文", failure.getMessage());
        verify(plans, never()).selectById(any());
        verify(plans, never()).selectOne(any(Wrapper.class), eq(false));
        verify(stocks, never()).selectList(any(Wrapper.class));
    }
}
