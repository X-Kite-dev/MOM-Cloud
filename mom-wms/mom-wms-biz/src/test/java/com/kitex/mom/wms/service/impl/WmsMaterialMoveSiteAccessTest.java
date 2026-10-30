package com.kitex.mom.wms.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.wms.entity.WmsMaterialMoveSiteEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsMaterialMoveSiteAccessTest {
    private final PermissionService permissions = mock(PermissionService.class);
    private final WmsMaterialMoveSiteAccess access = new WmsMaterialMoveSiteAccess(permissions);

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
    void warehousePermissionAllowsOnlyItsOwnMovementOperations() {
        when(permissions.hasPermission("wms_wmsMaterialMoveSite_view_W1")).thenReturn(true);
        when(permissions.hasPermission("wms_wmsMaterialMoveSite_add_W1")).thenReturn(true);
        when(permissions.hasPermission("wms_wmsMaterialMoveSite_export_W1")).thenReturn(true);

        assertDoesNotThrow(() -> access.requireView("W1"));
        assertDoesNotThrow(() -> access.requireMove("W1"));
        assertDoesNotThrow(() -> access.requireExport("W1"));
        assertThrows(CheckedException.class, () -> access.requireView("W2"));
        assertThrows(CheckedException.class, () -> access.requireMove("W2"));
        assertThrows(CheckedException.class, () -> access.requireExport("W2"));
        assertThrows(CheckedException.class, () -> access.requireView(""));
        verify(permissions).hasPermission("wms_wmsMaterialMoveSite_view_W1");
    }

    @Test
    void globalPermissionAllowsFactoryWideReadWithoutWarehouseFilter() {
        when(permissions.hasPermission("wms_wmsMaterialMoveSite_view")).thenReturn(true);

        assertDoesNotThrow(() -> access.requireView(null));
        assertDoesNotThrow(() -> access.requireView("W2"));
        assertThrows(CheckedException.class, () -> access.requireMove("W2"));
    }

    @Test
    void rowMustMatchCurrentTenantFactoryAndAuthorizedWarehouse() {
        when(permissions.hasPermission("wms_wmsMaterialMoveSite_view_W1")).thenReturn(true);
        var row = new WmsMaterialMoveSiteEntity();
        row.setWarehouseCode("W1");
        row.setFactoryCode("F1");
        row.setTenantId(1L);
        assertDoesNotThrow(() -> access.requireViewRow(row));

        row.setTenantId(2L);
        assertThrows(CheckedException.class, () -> access.requireViewRow(row));
        row.setTenantId(1L);
        row.setFactoryCode("F2");
        assertThrows(CheckedException.class, () -> access.requireViewRow(row));
        row.setFactoryCode("F1");
        row.setWarehouseCode("W2");
        assertThrows(CheckedException.class, () -> access.requireViewRow(row));
        assertThrows(CheckedException.class, () -> access.requireViewRow(null));
    }

    @Test
    void missingOrSkippedOrganizationCannotUseGlobalPermission() {
        when(permissions.hasPermission("wms_wmsMaterialMoveSite_view")).thenReturn(true);
        TenantContextHolder.clear();
        assertThrows(CheckedException.class, () -> access.requireView("W1"));
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.clear();
        assertThrows(CheckedException.class, () -> access.requireView("W1"));
    }
}
