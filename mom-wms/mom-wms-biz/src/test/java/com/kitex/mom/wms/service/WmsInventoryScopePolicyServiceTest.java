package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.mapper.WmsInventoryScopePolicyMapper;
import com.kitex.mom.wms.mapper.WmsWarehouseMenuMapper;
import com.kitex.mom.wms.service.impl.WmsInventoryWarehousePermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsInventoryScopePolicyServiceTest {
    private final PermissionService permissions = mock(PermissionService.class);
    private final WmsInventoryScopePolicyMapper policies = mock(WmsInventoryScopePolicyMapper.class);
    private final WmsWarehouseMenuMapper menus = mock(WmsWarehouseMenuMapper.class);
    private final RemoteWarehouseService warehouses = mock(RemoteWarehouseService.class);
    private final WmsInventoryScopePolicyService service = new WmsInventoryScopePolicyService(
            policies, new WmsInventoryWarehousePermission(permissions), permissions, menus, warehouses);

    @BeforeEach
    void setScope() {
        TenantContextHolder.setTenantId(11L);
        FactoryContextHolder.setFactoryCode("F1");
        when(permissions.hasPermission("wms_wmsInsideInventoryPlan_add_W1")).thenReturn(true);
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
    void effectiveReturnsProductDefaultOrScopedWarehouseOverride() {
        var defaults = service.effective("W1");
        assertEquals("ALL_POSITIVE", defaults.ruleCode());
        assertNull(defaults.thresholdDays());
        assertEquals("PRODUCT_DEFAULT", defaults.source());
        verify(policies).find(11L, "F1", "W1");

        when(policies.find(11L, "F1", "W1"))
                .thenReturn(new WmsInventoryScopePolicyMapper.Row("NEAR_EXPIRY", 14, 2, false));
        var configured = service.effective("W1");
        assertEquals("NEAR_EXPIRY", configured.ruleCode());
        assertEquals(14, configured.thresholdDays());
        assertEquals(2, configured.version());
        assertEquals("WAREHOUSE_CONFIG", configured.source());

        when(policies.find(11L, "F1", "W1"))
                .thenReturn(new WmsInventoryScopePolicyMapper.Row("ALL_POSITIVE", null, 3, true));
        var inherited = service.effective("W1");
        assertEquals("PRODUCT_DEFAULT", inherited.source());
        assertEquals(3, inherited.version());
    }

    @Test
    void saveChecksPermissionRuleAndVersionBeforeWriting() {
        var command = new WmsInventoryScopePolicyService.SaveCommand("W1", "LONG_STAY", 90, 0);
        assertThrows(CheckedException.class, () -> service.save(command));
        when(permissions.hasPermission("wms_wmsWarehouseMenu_add")).thenReturn(true);
        assertThrows(CheckedException.class, () -> service.save(
                new WmsInventoryScopePolicyService.SaveCommand("W1", "EXTERNAL_OWNER", 30, 0)));
        assertThrows(CheckedException.class, () -> service.save(
                new WmsInventoryScopePolicyService.SaveCommand("W1", "LONG_STAY", 0, 0)));
        when(policies.find(11L, "F1", "W1"))
                .thenReturn(new WmsInventoryScopePolicyMapper.Row("LONG_STAY", 60, 2, false));
        assertThrows(CheckedException.class, () -> service.save(command));
        verify(policies, never()).upsert(any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void disabledWarehouseCannotBeConfigured() {
        when(permissions.hasPermission("wms_wmsWarehouseMenu_add")).thenReturn(true);
        var disabled = new MdmWarehouseEntity().setWarehouseCode("W1").setFactoryCode("F1").setStatus(false);
        disabled.setTenantId(11L);
        when(warehouses.getMenuConfigurationWarehouses()).thenReturn(R.ok(List.of(disabled)));

        assertThrows(CheckedException.class, () -> service.save(
                new WmsInventoryScopePolicyService.SaveCommand("W1", "ALL_POSITIVE", null, 0)));
        verify(policies, never()).upsert(any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void saveAndResetKeepTheWarehouseAndExpectedVersion() {
        when(permissions.hasPermission("wms_wmsWarehouseMenu_add")).thenReturn(true);
        when(policies.upsert(11L, "F1", "W1", "NEAR_EXPIRY", 14, 0, "operator")).thenReturn(1);

        var saved = service.save(new WmsInventoryScopePolicyService.SaveCommand("W1", "NEAR_EXPIRY", 14, 0));
        assertEquals(1, saved.version());
        assertEquals("WAREHOUSE_CONFIG", saved.source());
        verify(menus).lockConfiguration("WMS_WAREHOUSE_MENU:11:F1");

        when(policies.find(11L, "F1", "W1"))
                .thenReturn(new WmsInventoryScopePolicyMapper.Row("NEAR_EXPIRY", 14, 1, false));
        when(policies.resetVersion(11L, "F1", "W1", 1, "operator")).thenReturn(1);
        var reset = service.reset(new WmsInventoryScopePolicyService.ResetCommand("W1", 1));
        assertEquals("PRODUCT_DEFAULT", reset.source());
        assertEquals(2, reset.version());
        verify(policies).resetVersion(11L, "F1", "W1", 1, "operator");
    }

    @Test
    void staleResetCannotRemoveAnotherAdministratorsRule() {
        when(permissions.hasPermission("wms_wmsWarehouseMenu_add")).thenReturn(true);
        when(policies.find(11L, "F1", "W1"))
                .thenReturn(new WmsInventoryScopePolicyMapper.Row("LONG_STAY", 60, 2, false));

        assertThrows(CheckedException.class, () -> service.reset(
                new WmsInventoryScopePolicyService.ResetCommand("W1", 1)));
        verify(policies, never()).resetVersion(eq(11L), eq("F1"), eq("W1"), anyInt(), any());
    }

    @Test
    void ruleLoadedBeforeResetCannotOverwriteTheInheritedDefault() {
        when(permissions.hasPermission("wms_wmsWarehouseMenu_add")).thenReturn(true);
        when(policies.find(11L, "F1", "W1"))
                .thenReturn(new WmsInventoryScopePolicyMapper.Row("ALL_POSITIVE", null, 2, true));

        assertThrows(CheckedException.class, () -> service.save(
                new WmsInventoryScopePolicyService.SaveCommand("W1", "NEAR_EXPIRY", 14, 1)));
        verify(policies, never()).upsert(any(), any(), any(), any(), any(), anyInt(), any());
    }
}
