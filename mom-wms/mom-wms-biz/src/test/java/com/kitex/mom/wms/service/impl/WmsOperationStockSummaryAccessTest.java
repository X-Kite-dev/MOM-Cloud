package com.kitex.mom.wms.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WmsOperationStockSummaryAccessTest {
    private final PermissionService permissions = mock(PermissionService.class);
    private final WmsOperationStockSummaryAccess access = new WmsOperationStockSummaryAccess(permissions);

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
    void movementSummaryNeedsMovementPermissionForItsSourceWarehouse() {
        when(permissions.hasPermission("wms_wmsMaterialMoveSite_add", "wms_wmsMaterialMoveSite_add_W1"))
                .thenReturn(true);

        assertDoesNotThrow(() -> access.require("move_site", "W1"));
        assertThrows(CheckedException.class, () -> access.require("move_site", "W2"));
        assertThrows(CheckedException.class, () -> access.require("allocation_out", "W1"));
    }

    @Test
    void allocationSummaryNeedsAllocationAddPermissionForItsSourceWarehouse() {
        when(permissions.hasPermission("wms_wmsAllocationBill_add", "wms_wmsAllocationBill_add_W2"))
                .thenReturn(true);

        assertDoesNotThrow(() -> access.require("allocation_out", "W2"));
        assertThrows(CheckedException.class, () -> access.require("allocation_out", "W1"));
        assertThrows(CheckedException.class, () -> access.require("move_site", "W2"));
    }

    @Test
    void unsupportedContextCannotTurnSummaryIntoGenericStockRead() {
        when(permissions.hasPermission("wms_wmsMaterialMoveSite_add", "wms_wmsMaterialMoveSite_add_W1"))
                .thenReturn(true);

        assertThrows(CheckedException.class, () -> access.require(null, "W1"));
        assertThrows(CheckedException.class, () -> access.require("unknown", "W1"));
        assertThrows(CheckedException.class, () -> access.require("move_site", ""));
        assertThrows(CheckedException.class, () -> access.require("move_site", "W*"));
        TenantContextHolder.clear();
        assertThrows(CheckedException.class, () -> access.require("move_site", "W1"));
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.clear();
        assertThrows(CheckedException.class, () -> access.require("move_site", "W1"));
        FactoryContextHolder.setFactoryCode("F1");
        TenantContextHolder.setTenantSkip();
        assertThrows(CheckedException.class, () -> access.require("move_site", "W1"));
    }
}
