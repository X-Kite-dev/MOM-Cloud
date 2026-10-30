package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.mapper.WmsInBillMapper;
import com.kitex.mom.wms.mapper.WmsInsideInventoryPlanMapper;
import com.kitex.mom.wms.mapper.WmsOutBillMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

class WmsWarehousePendingWorkServiceTest {
    private final PermissionService permissions = mock(PermissionService.class);
    private final WmsInBillMapper inBills = mock(WmsInBillMapper.class);
    private final WmsOutBillMapper outBills = mock(WmsOutBillMapper.class);
    private final WmsInsideInventoryPlanMapper inventoryPlans = mock(WmsInsideInventoryPlanMapper.class);
    private final WmsWarehousePendingWorkService service =
            new WmsWarehousePendingWorkService(permissions, inBills, outBills, inventoryPlans);

    @BeforeAll
    static void initTables() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "dashboard-in-bill"),
                WmsInBillEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "dashboard-out-bill"),
                WmsOutBillEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "dashboard-inventory"),
                WmsInsideInventoryPlanEntity.class);
    }

    @BeforeEach
    void setScope() {
        TenantContextHolder.setTenantId(8L);
        FactoryContextHolder.setFactoryCode("F1");
    }

    @AfterEach
    void clearScope() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void countsOnlyAuthorizedBillsInsideSelectedWarehouse() {
        when(permissions.hasPermission("wms_bill_view_in_W1")).thenReturn(true);
        when(inBills.selectCount(any())).thenReturn(3L);

        var result = service.query("W1");

        assertEquals("W1", result.warehouseCode());
        assertEquals(3L, result.inboundPendingReview());
        assertNull(result.outboundPendingReview());
        assertNull(result.inventoryPending());
        verifyZeroInteractions(outBills, inventoryPlans);
        var query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(inBills).selectCount(query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("tenant_id"));
        assertTrue(sql.contains("factory_code"));
        assertTrue(sql.contains("warehouse_code"));
        assertTrue(sql.contains("state"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(8L));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("F1"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("W1"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("1"));
    }

    @Test
    void noBillPermissionDoesNotReadEitherTable() {
        var result = service.query("W1");

        assertNull(result.inboundPendingReview());
        assertNull(result.outboundPendingReview());
        assertNull(result.inventoryPending());
        verifyZeroInteractions(inBills, outBills, inventoryPlans);
    }

    @Test
    void outboundCountUsesItsOwnPermissionAndOrganizationScope() {
        when(permissions.hasPermission("wms_bill_view_out_W2")).thenReturn(true);
        when(outBills.selectCount(any())).thenReturn(5L);

        var result = service.query("W2");

        assertNull(result.inboundPendingReview());
        assertEquals(5L, result.outboundPendingReview());
        assertNull(result.inventoryPending());
        verifyZeroInteractions(inBills, inventoryPlans);
        var query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(outBills).selectCount(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("warehouse_code"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(8L));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("F1"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("W2"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("1"));
    }

    @Test
    void inventoryPendingCountsOnlyOpenPlansInAuthorizedWarehouse() {
        when(permissions.hasPermission("wms_wmsInsideInventoryPlan_view_W2")).thenReturn(true);
        when(inventoryPlans.selectCount(any())).thenReturn(4L);

        var result = service.query("W2");

        assertNull(result.inboundPendingReview());
        assertNull(result.outboundPendingReview());
        assertEquals(4L, result.inventoryPending());
        verifyZeroInteractions(inBills, outBills);
        var query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(inventoryPlans).selectCount(query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("tenant_id"));
        assertTrue(sql.contains("factory_code"));
        assertTrue(sql.contains("warehouse_code"));
        assertTrue(sql.contains("handle_state"));
        assertTrue(sql.contains("status"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(8L));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("F1"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("W2"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("0"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("1"));
    }

    @Test
    void missingOrganizationOrWarehouseCannotReadPendingWork() {
        TenantContextHolder.clear();
        assertThrows(CheckedException.class, () -> service.query("W1"));
        TenantContextHolder.setTenantId(8L);
        assertThrows(CheckedException.class, () -> service.query(" "));
        assertThrows(CheckedException.class, () -> service.query("*"));
        verifyZeroInteractions(inBills, outBills, inventoryPlans);
    }
}
