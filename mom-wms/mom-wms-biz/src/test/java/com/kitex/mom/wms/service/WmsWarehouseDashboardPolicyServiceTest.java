package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.mapper.WmsWarehouseDashboardPolicyMapper;
import com.kitex.mom.wms.mapper.WmsWarehouseMenuMapper;
import com.kitex.mom.wms.service.impl.WmsLiveStockReadAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsWarehouseDashboardPolicyServiceTest {
    private final PermissionService permissions = mock(PermissionService.class);
    private final WmsWarehouseDashboardPolicyMapper policies = mock(WmsWarehouseDashboardPolicyMapper.class);
    private final WmsWarehouseMenuMapper menus = mock(WmsWarehouseMenuMapper.class);
    private final RemoteWarehouseService warehouses = mock(RemoteWarehouseService.class);
    private final WmsWarehouseDashboardPolicyService service = new WmsWarehouseDashboardPolicyService(
            policies, new WmsLiveStockReadAccess(permissions), permissions, menus, warehouses);

    @BeforeEach
    void setScope() {
        TenantContextHolder.setTenantId(11L);
        FactoryContextHolder.setFactoryCode("F1");
        when(permissions.hasPermission("wms_wmsLiveStock_view_stock_detail_W1")).thenReturn(true);
        var warehouse = new MdmWarehouseEntity().setWarehouseCode("W1").setFactoryCode("F1").setStatus(true);
        warehouse.setTenantId(11L);
        when(warehouses.getMenuConfigurationWarehouses()).thenReturn(R.ok(List.of(warehouse)));
        MomUser user = mock(MomUser.class);
        when(user.getUsername()).thenReturn("operator");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null));
    }

    @AfterEach
    void clearScope() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingWarehousePolicyUsesProductDefaultsWithinTheCurrentScope() {
        var result = service.effective("W1");

        assertEquals(30, result.nearExpiryDays());
        assertEquals(90, result.slowMovingDays());
        assertEquals(0, result.version());
        assertEquals("PRODUCT_DEFAULT", result.source());
        verify(policies).find(11L, "F1", "W1");
    }

    @Test
    void savedWarehousePolicyOverridesDefaultsForOnlyItsScope() {
        when(policies.find(11L, "F1", "W1")).thenReturn(
                new WmsWarehouseDashboardPolicyMapper.Row(45, 120, 3));

        var result = service.effective("W1");

        assertEquals(45, result.nearExpiryDays());
        assertEquals(120, result.slowMovingDays());
        assertEquals(3, result.version());
        assertEquals("WAREHOUSE_CONFIG", result.source());
    }

    @Test
    void savingRequiresWarehouseConfigurationPermissionAndValidThresholds() {
        var command = new WmsWarehouseDashboardPolicyService.SaveCommand("W1", 45, 120, 0);

        assertThrows(CheckedException.class, () -> service.save(command));
        verify(policies, never()).upsert(anyLong(), anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyString());

        when(permissions.hasPermission("wms_wmsWarehouseMenu_add")).thenReturn(true);
        assertThrows(CheckedException.class, () -> service.save(
                new WmsWarehouseDashboardPolicyService.SaveCommand("W1", 0, 120, 0)));
        verify(policies, never()).upsert(anyLong(), anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyString());
    }

    @Test
    void staleVersionCannotOverwriteAnotherAdministratorsSavedPolicy() {
        when(permissions.hasPermission("wms_wmsWarehouseMenu_add")).thenReturn(true);
        when(policies.find(11L, "F1", "W1")).thenReturn(
                new WmsWarehouseDashboardPolicyMapper.Row(45, 120, 2));

        assertThrows(CheckedException.class, () -> service.save(
                new WmsWarehouseDashboardPolicyService.SaveCommand("W1", 60, 150, 1)));
        verify(policies, never()).upsert(anyLong(), anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyString());
    }

    @Test
    void disabledWarehouseCannotRecreateConfigurationAfterCleanup() {
        when(permissions.hasPermission("wms_wmsWarehouseMenu_add")).thenReturn(true);
        var disabled = new MdmWarehouseEntity().setWarehouseCode("W1").setFactoryCode("F1").setStatus(false);
        disabled.setTenantId(11L);
        when(warehouses.getMenuConfigurationWarehouses()).thenReturn(R.ok(List.of(disabled)));

        assertThrows(CheckedException.class, () -> service.save(
                new WmsWarehouseDashboardPolicyService.SaveCommand("W1", 45, 120, 0)));
        verify(policies, never()).upsert(anyLong(), anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyString());
    }

    @Test
    void saveUsesCurrentVersionAndReturnsTheNewVersion() {
        when(permissions.hasPermission("wms_wmsWarehouseMenu_add")).thenReturn(true);
        when(policies.find(11L, "F1", "W1")).thenReturn(
                new WmsWarehouseDashboardPolicyMapper.Row(30, 90, 2));
        when(policies.upsert(11L, "F1", "W1", 45, 120, 2, "operator")).thenReturn(1);

        var result = service.save(new WmsWarehouseDashboardPolicyService.SaveCommand("W1", 45, 120, 2));

        assertEquals(3, result.version());
        assertEquals(45, result.nearExpiryDays());
        verify(menus).lockConfiguration("WMS_WAREHOUSE_MENU:11:F1");
    }

    @Test
    void losingTheAtomicUpdateReportsAStaleVersion() {
        when(permissions.hasPermission("wms_wmsWarehouseMenu_add")).thenReturn(true);
        when(policies.upsert(11L, "F1", "W1", 45, 120, 0, "operator")).thenReturn(0);

        assertThrows(CheckedException.class, () -> service.save(
                new WmsWarehouseDashboardPolicyService.SaveCommand("W1", 45, 120, 0)));
    }

    @Test
    void resetRemovesOnlyTheVersionSeenByTheAdministratorAndRestoresInheritance() {
        when(permissions.hasPermission("wms_wmsWarehouseMenu_add")).thenReturn(true);
        when(policies.find(11L, "F1", "W1")).thenReturn(
                new WmsWarehouseDashboardPolicyMapper.Row(45, 120, 3));
        when(policies.deleteVersion(11L, "F1", "W1", 3)).thenReturn(1);

        var result = service.reset(new WmsWarehouseDashboardPolicyService.ResetCommand("W1", 3));

        assertEquals("PRODUCT_DEFAULT", result.source());
        assertEquals(0, result.version());
        assertEquals(30, result.nearExpiryDays());
        verify(policies).deleteVersion(11L, "F1", "W1", 3);
    }

    @Test
    void staleResetDoesNotDeleteAnotherAdministratorsConfiguration() {
        when(permissions.hasPermission("wms_wmsWarehouseMenu_add")).thenReturn(true);
        when(policies.find(11L, "F1", "W1")).thenReturn(
                new WmsWarehouseDashboardPolicyMapper.Row(45, 120, 3));

        assertThrows(CheckedException.class, () -> service.reset(
                new WmsWarehouseDashboardPolicyService.ResetCommand("W1", 2)));
        verify(policies, never()).deleteVersion(anyLong(), anyString(), anyString(), anyInt());
    }

    @Test
    void resetRequiresWarehouseConfigurationPermission() {
        assertThrows(CheckedException.class, () -> service.reset(
                new WmsWarehouseDashboardPolicyService.ResetCommand("W1", 1)));
        verify(policies, never()).deleteVersion(anyLong(), anyString(), anyString(), anyInt());
    }

    @Test
    void resetRejectsAnUpdateLostAfterTheInitialRead() {
        when(permissions.hasPermission("wms_wmsWarehouseMenu_add")).thenReturn(true);
        when(policies.find(11L, "F1", "W1")).thenReturn(
                new WmsWarehouseDashboardPolicyMapper.Row(45, 120, 3));
        when(policies.deleteVersion(11L, "F1", "W1", 3)).thenReturn(0);

        assertThrows(CheckedException.class, () -> service.reset(
                new WmsWarehouseDashboardPolicyService.ResetCommand("W1", 3)));
    }
}
