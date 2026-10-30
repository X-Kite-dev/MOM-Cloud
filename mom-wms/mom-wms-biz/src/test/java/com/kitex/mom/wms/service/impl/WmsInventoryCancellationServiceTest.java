package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.wms.dto.WmsInventoryCancelCommand;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.mapper.WmsInBillMapper;
import com.kitex.mom.wms.mapper.WmsInsideInventoryPlanDetailMapper;
import com.kitex.mom.wms.mapper.WmsInsideInventoryPlanMapper;
import com.kitex.mom.wms.mapper.WmsOutBillMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsInventoryCancellationServiceTest {
    private final WmsInsideInventoryPlanMapper plans = mock(WmsInsideInventoryPlanMapper.class);
    private final WmsInsideInventoryPlanDetailMapper details = mock(WmsInsideInventoryPlanDetailMapper.class);
    private final WmsInBillMapper inBills = mock(WmsInBillMapper.class);
    private final WmsOutBillMapper outBills = mock(WmsOutBillMapper.class);
    private final WmsInsideInventoryPlanEntity plan = new WmsInsideInventoryPlanEntity();
    private final WmsInsideInventoryPlanDetailEntity row = new WmsInsideInventoryPlanDetailEntity();

    @BeforeAll
    static void initializeMetadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "inventory-cancel");
        for (var entity : List.of(WmsInsideInventoryPlanEntity.class, WmsInsideInventoryPlanDetailEntity.class,
                WmsInBillEntity.class, WmsOutBillEntity.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var user = mock(MomUser.class);
        when(user.getUsername()).thenReturn("counter");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        plan.setId(1L);
        plan.setTenantId(1L);
        plan.setFactoryCode("F1");
        plan.setWarehouseCode("W1");
        plan.setStatus("1");
        plan.setHandleState("0");
        row.setId(2L);
        row.setPlanId(1L);
        row.setStatus("1");
        row.setPlStatus("0");
        row.setCurrentNumber(5d);
        row.setInventoryNum(7d);
        when(plans.selectOne(any())).thenReturn(plan);
        when(details.selectList(any())).thenReturn(List.of(row));
        when(inBills.selectCount(any())).thenReturn(0L);
        when(outBills.selectCount(any())).thenReturn(0L);
        when(plans.updateById(any(WmsInsideInventoryPlanEntity.class))).thenReturn(1);
    }

    @AfterEach
    void clearScope() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void unadjustedPlanCanBeCancelledWithoutDeletingCountedRows() {
        assertTrue(service().cancel(command()));

        assertEquals("2", plan.getStatus());
        assertEquals("2", plan.getHandleState());
        assertEquals("库存已变动，重新盘点", plan.getCancelReason());
        assertEquals("counter", plan.getCancelBy());
        assertNotNull(plan.getCancelTime());
        verify(details, never()).delete(any());
        InOrder order = inOrder(details, inBills, outBills, plans);
        order.verify(details).selectList(any());
        order.verify(inBills).selectCount(any());
        order.verify(outBills).selectCount(any());
        order.verify(plans).updateById(plan);
    }

    @Test
    void draftPlanCanBeCancelledBeforeAnyCountIsEntered() {
        plan.setStatus("0");
        row.setStatus("0");
        row.setInventoryNum(null);

        assertTrue(service().cancel(command()));

        assertEquals("2", plan.getStatus());
        verify(details, never()).delete(any());
    }

    @Test
    void balancedFinalizedLineCanBeCancelledWithoutInventoryBills() {
        row.setInventoryNum(5d);
        row.setPlStatus("1");

        assertTrue(service().cancel(command()));

        assertEquals("2", plan.getHandleState());
    }

    @Test
    void fullyHandledPlanCannotBeCancelled() {
        plan.setHandleState("1");

        assertThrows(CheckedException.class, () -> service().cancel(command()));

        verify(plans, never()).updateById(any(WmsInsideInventoryPlanEntity.class));
    }

    @Test
    void anyAdjustedLineBlocksWholePlanCancellation() {
        var another = new WmsInsideInventoryPlanDetailEntity();
        another.setId(3L);
        another.setCurrentNumber(4d);
        another.setInventoryNum(3d);
        another.setPlStatus("0");
        when(details.selectList(any())).thenReturn(List.of(row, another));
        when(inBills.selectCount(any())).thenReturn(0L, 1L);

        assertThrows(CheckedException.class, () -> service().cancel(command()));

        verify(plans, never()).updateById(any(WmsInsideInventoryPlanEntity.class));
    }

    @Test
    void alreadyProcessedDifferenceCannotBeCancelledEvenWhenReceiptIsMissing() {
        row.setPlStatus("1");

        assertThrows(CheckedException.class, () -> service().cancel(command()));

        verify(plans, never()).updateById(any(WmsInsideInventoryPlanEntity.class));
    }

    @Test
    void sameRequestCanBeRetriedButItsReasonCannotBeChanged() {
        var service = service();
        var command = command();
        assertTrue(service.cancel(command));
        assertTrue(service.cancel(command));
        command.setReason("另一个原因");

        assertThrows(CheckedException.class, () -> service.cancel(command));

        verify(plans).updateById(plan);
    }

    private WmsInventoryCancelCommand command() {
        var command = new WmsInventoryCancelCommand();
        command.setId(1L);
        command.setRequestId("cancel-request-0001");
        command.setReason("库存已变动，重新盘点");
        return command;
    }

    private WmsInventoryCancellationService service() {
        return new WmsInventoryCancellationService(new WmsInventoryPlanAccess(plans, details), plans,
                inBills, outBills, org.mockito.Mockito.mock(WmsInventoryAdjustmentApprovalService.class));
    }
}
