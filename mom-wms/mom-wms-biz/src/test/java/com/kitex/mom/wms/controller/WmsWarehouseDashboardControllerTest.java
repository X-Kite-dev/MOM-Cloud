package com.kitex.mom.wms.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.wms.agent.dto.WmsInventoryHealthDTO;
import com.kitex.mom.wms.agent.service.WmsInventoryHealthQueryService;
import com.kitex.mom.wms.service.WmsWarehousePendingWorkService;
import com.kitex.mom.wms.service.WmsWarehouseDashboardPolicyService;
import com.kitex.mom.wms.service.impl.WmsLiveStockReadAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

class WmsWarehouseDashboardControllerTest {
    private final PermissionService permissions = mock(PermissionService.class);
    private final WmsInventoryHealthQueryService health = mock(WmsInventoryHealthQueryService.class);
    private final WmsWarehousePendingWorkService pendingWork = mock(WmsWarehousePendingWorkService.class);
    private final WmsWarehouseDashboardPolicyService policy = mock(WmsWarehouseDashboardPolicyService.class);
    private final WmsWarehouseDashboardController controller = new WmsWarehouseDashboardController(
            new WmsLiveStockReadAccess(permissions), health, pendingWork, policy);

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
    void selectedWarehouseUsesItsOwnStockPermissionAndCurrentFactory() {
        when(permissions.hasPermission("wms_wmsLiveStock_view_stock_detail_W1")).thenReturn(true);
        when(health.query(any())).thenReturn(new WmsInventoryHealthDTO.Evidence().setWarehouseCode("W1"));

        var result = controller.inventoryHealth("W1", 30, 90);

        assertEquals("W1", result.getData().getWarehouseCode());
        var query = ArgumentCaptor.forClass(WmsInventoryHealthDTO.Query.class);
        verify(health).query(query.capture());
        assertEquals("F1", query.getValue().getFactoryCode());
        assertEquals("W1", query.getValue().getWarehouseCode());
        assertEquals(30, query.getValue().getNearExpiryDays());
        assertEquals(90, query.getValue().getSlowMovingDays());
    }

    @Test
    void absentQueryThresholdsInheritSavedWarehousePolicy() {
        when(permissions.hasPermission("wms_wmsLiveStock_view_stock_detail_W1")).thenReturn(true);
        when(policy.effective("W1")).thenReturn(
                new WmsWarehouseDashboardPolicyService.Policy("W1", 45, 120, 2, "WAREHOUSE_CONFIG"));
        when(health.query(any())).thenReturn(new WmsInventoryHealthDTO.Evidence().setWarehouseCode("W1"));

        controller.inventoryHealth("W1", null, null);

        var query = ArgumentCaptor.forClass(WmsInventoryHealthDTO.Query.class);
        verify(health).query(query.capture());
        assertEquals(45, query.getValue().getNearExpiryDays());
        assertEquals(120, query.getValue().getSlowMovingDays());
    }

    @Test
    void unassignedWarehouseCannotRevealDashboardEvidence() {
        assertThrows(CheckedException.class, () -> controller.inventoryHealth("W2", 30, 90));
        verifyZeroInteractions(health);
    }

    @Test
    void dashboardRequiresOneExplicitWarehouseEvenWithGlobalStockPermission() {
        when(permissions.hasPermission("wms_wmsLiveStock_view")).thenReturn(true);

        assertThrows(CheckedException.class, () -> controller.inventoryHealth(" ", 30, 90));
        verifyZeroInteractions(health);
    }

    @Test
    void missingTenantContextCannotReadDashboard() {
        TenantContextHolder.clear();

        assertThrows(CheckedException.class, () -> controller.inventoryHealth("W1", 30, 90));
        verifyZeroInteractions(health);
    }

    @Test
    void missingFactoryContextCannotReadDashboard() {
        FactoryContextHolder.clear();

        assertThrows(CheckedException.class, () -> controller.inventoryHealth("W1", 30, 90));
        verifyZeroInteractions(health);
    }

    @Test
    void exceptionStockIdIsSerializedWithoutJavascriptRounding() throws Exception {
        var stock = new WmsInventoryHealthDTO.ExceptionStock().setStockId(2113981289307525122L);

        String json = new ObjectMapper().writeValueAsString(stock);

        assertTrue(json.contains("\"stockId\":\"2113981289307525122\""));
    }
}
