package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.mapper.WmsInsideInventoryPlanDetailMapper;
import com.kitex.mom.wms.mapper.WmsInsideInventoryPlanMapper;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsInventoryPlanLifecycleTest {
    private final WmsInsideInventoryPlanMapper plans = mock(WmsInsideInventoryPlanMapper.class);
    private final WmsInsideInventoryPlanDetailMapper details = mock(WmsInsideInventoryPlanDetailMapper.class);
    private final WmsLiveStockMapper stocks = mock(WmsLiveStockMapper.class);
    private final WmsInventoryAdjustmentService adjustments = mock(WmsInventoryAdjustmentService.class);
    private final WmsInsideInventoryPlanEntity plan = new WmsInsideInventoryPlanEntity();
    private final WmsInsideInventoryPlanDetailEntity row = new WmsInsideInventoryPlanDetailEntity();

    @BeforeAll
    static void initializeMetadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "inventory-plan-lifecycle");
        TableInfoHelper.initTableInfo(assistant, WmsInsideInventoryPlanEntity.class);
        TableInfoHelper.initTableInfo(assistant, WmsInsideInventoryPlanDetailEntity.class);
    }

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        plan.setId(1L);
        plan.setTenantId(1L);
        plan.setFactoryCode("F1");
        plan.setStatus("1");
        plan.setHandleState("0");
        row.setId(2L);
        row.setPlanId(1L);
        row.setTenantId(1L);
        row.setFactoryCode("F1");
        row.setStatus("1");
        row.setPlStatus("0");
        row.setInventoryNum(5d);
        when(plans.selectOne(any())).thenReturn(plan);
        when(details.selectOne(any())).thenReturn(row);
        when(details.selectList(any())).thenReturn(List.of(row));
    }

    @AfterEach
    void clearScope() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void skippedTenantScopeCannotCompleteInventoryPlan() {
        TenantContextHolder.setTenantSkip();

        assertThrows(CheckedException.class, () -> service().appInventorySuccess(1L));

        verify(plans, never()).selectOne(any());
        verify(plans, never()).updateById(any(WmsInsideInventoryPlanEntity.class));
    }

    @Test
    void skippedFactoryScopeCannotCompleteInventoryPlan() {
        FactoryContextHolder.setFactorySkip();

        assertThrows(CheckedException.class, () -> service().appInventorySuccess(1L));

        verify(plans, never()).selectOne(any());
        verify(plans, never()).updateById(any(WmsInsideInventoryPlanEntity.class));
    }

    @Test
    void webCountCannotRewriteFinalizedPlan() {
        var command = new WmsInsideInventoryPlanEntity();
        command.setId(1L);
        var submitted = new WmsInsideInventoryPlanDetailEntity();
        submitted.setId(2L);
        submitted.setInventoryNum(7d);
        command.setWmsInsideInventoryPlanDetailList(List.of(submitted));

        assertThrows(CheckedException.class, () -> service().inventory(command));

        verify(details, never()).updateById(any(WmsInsideInventoryPlanDetailEntity.class));
        verify(plans, never()).updateById(any(WmsInsideInventoryPlanEntity.class));
    }

    @Test
    void mobileCountCannotRewriteFinalizedPlan() {
        var submitted = new WmsInsideInventoryPlanDetailEntity();
        submitted.setId(2L);
        submitted.setInventoryNum(7d);

        assertThrows(CheckedException.class, () -> service().appInventory(submitted));

        verify(details, never()).updateById(any(WmsInsideInventoryPlanDetailEntity.class));
    }

    @Test
    void mobileCompletionCannotReopenCancelledPlan() {
        plan.setStatus("2");
        plan.setHandleState("2");
        row.setCurrentNumber(5d);

        assertThrows(CheckedException.class, () -> service().appInventorySuccess(1L));

        verify(plans, never()).updateById(any(WmsInsideInventoryPlanEntity.class));
    }

    @Test
    void detailCannotBeHandledBeforeWholePlanCountIsFinalized() {
        plan.setStatus("0");
        row.setCurrentNumber(5d);

        assertThrows(CheckedException.class, () -> service().detailComplete(2L));

        verify(details, never()).updateById(any(WmsInsideInventoryPlanDetailEntity.class));
    }

    @Test
    void planCannotBeHandledBeforeWholePlanCountIsFinalized() {
        plan.setStatus("0");
        row.setCurrentNumber(5d);

        assertThrows(CheckedException.class, () -> service().complete(1L));

        verify(details, never()).updateById(any(WmsInsideInventoryPlanDetailEntity.class));
        verify(plans, never()).updateById(any(WmsInsideInventoryPlanEntity.class));
    }

    @Test
    void zeroDifferenceCannotCompleteAfterLiveStockChanges() {
        zeroDifferenceAtSite();
        when(stocks.selectList(any())).thenReturn(List.of(stock(6d)));

        assertThrows(CheckedException.class, () -> service().complete(1L));

        verify(details, never()).updateById(any(WmsInsideInventoryPlanDetailEntity.class));
        verify(plans, never()).updateById(any(WmsInsideInventoryPlanEntity.class));
    }

    @Test
    void zeroDifferenceCompletesWhenLiveStockStillMatchesSnapshot() {
        zeroDifferenceAtSite();
        when(stocks.selectList(any())).thenReturn(List.of(stock(5d)));
        when(details.updateById(any(WmsInsideInventoryPlanDetailEntity.class))).thenReturn(1);
        when(plans.updateById(any(WmsInsideInventoryPlanEntity.class))).thenReturn(1);

        assertTrue(service().complete(1L));

        verify(stocks).selectList(any());
        verify(details).updateById(any(WmsInsideInventoryPlanDetailEntity.class));
    }

    @Test
    void processedZeroDifferenceCannotCloseWhenAnInventoryBillExists() {
        zeroDifferenceAtSite();
        row.setPlStatus("1");
        doThrow(new CheckedException("零差异明细存在损益单据"))
                .when(adjustments).requireReceipt(row);
        when(plans.updateById(any(WmsInsideInventoryPlanEntity.class))).thenReturn(1);

        assertThrows(CheckedException.class, () -> service().complete(1L));

        verify(plans, never()).updateById(any(WmsInsideInventoryPlanEntity.class));
    }

    private void zeroDifferenceAtSite() {
        plan.setWarehouseCode("W1");
        row.setWarehouseCode("W1");
        row.setMatCode("M1");
        row.setSiteCode("S1");
        row.setMatUnit("PCS");
        row.setCurrentNumber(5d);
    }

    private WmsLiveStockEntity stock(double quantity) {
        var stock = new WmsLiveStockEntity();
        stock.setMatNum(quantity);
        stock.setMatUnit("PCS");
        return stock;
    }

    @Test
    void deletingUntouchedPlanMarksParentBeforeDetails() {
        plan.setStatus("0");
        row.setStatus("0");
        when(details.delete(any())).thenReturn(1);
        when(plans.deleteById(1L)).thenReturn(1);

        assertTrue(service().removeDeep(new Long[]{1L}));

        InOrder order = inOrder(details, plans);
        order.verify(plans).deleteById(1L);
        order.verify(details).delete(any());
    }

    @Test
    void incompleteDetailDeletionFailsTheTransactionalDelete() {
        plan.setStatus("0");
        row.setStatus("0");
        when(plans.deleteById(1L)).thenReturn(1);
        when(details.delete(any())).thenReturn(0);

        assertThrows(CheckedException.class, () -> service().removeDeep(new Long[]{1L}));

        verify(plans).deleteById(1L);
    }

    private WmsInsideInventoryPlanServiceImpl service() {
        var target = new WmsInsideInventoryPlanServiceImpl(details, stocks, null, null, null,
                new WmsInventoryPlanAccess(plans, details), null, adjustments);
        ReflectionTestUtils.setField(target, "baseMapper", plans);
        return target;
    }
}
