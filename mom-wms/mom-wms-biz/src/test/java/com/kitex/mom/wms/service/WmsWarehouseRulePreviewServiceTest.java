package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.wms.entity.WmsIdentifierPolicyEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

class WmsWarehouseRulePreviewServiceTest {
    private final WmsWarehouseBusinessGuard operations = mock(WmsWarehouseBusinessGuard.class);
    private final WmsIdentifierPolicyService identifiers = mock(WmsIdentifierPolicyService.class);
    private final WmsReservationMovementPolicyService reservations = mock(WmsReservationMovementPolicyService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final WmsWarehouseRulePreviewService service = new WmsWarehouseRulePreviewService(
            operations, identifiers, reservations, permissions);

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
    void missingScopeFailsBeforeAnyRuleRead() {
        when(permissions.hasPermission("wms_wmsWarehouseMenu_view")).thenReturn(true);
        TenantContextHolder.clear();

        assertThrows(CheckedException.class, () -> service.preview("W1", "purchase", true, "MAT-1"));
        verifyZeroInteractions(operations, identifiers, reservations);
    }

    @Test
    void menuPermissionDoesNotRevealOtherRuleDetails() {
        when(permissions.hasPermission("wms_wmsWarehouseMenu_view")).thenReturn(true);
        when(operations.evaluate("W1", "purchase", true))
                .thenReturn(new WmsWarehouseBusinessGuard.Evaluation(true, "WAREHOUSE_CONFIGURATION",
                        "/wms/inBiz/purchase", "已开放"));

        var result = service.preview("W1", "purchase", true, "MAT-1");

        assertTrue(result.operation().allowed());
        assertEquals("NO_PERMISSION", result.identifier().status());
        assertEquals("NOT_APPLICABLE", result.reservation().status());
        verifyZeroInteractions(identifiers, reservations);
    }

    @Test
    void purchasePreviewUsesEffectiveIdentifierResolver() {
        when(permissions.hasPermission("wms_wmsWarehouseMenu_view")).thenReturn(true);
        when(permissions.hasPermission("wms_identifierPolicy_view")).thenReturn(true);
        when(operations.evaluate("W1", "purchase", true))
                .thenReturn(new WmsWarehouseBusinessGuard.Evaluation(true, "WAREHOUSE_CONFIGURATION",
                        "/wms/inBiz/purchase", "已开放"));
        WmsIdentifierPolicyEntity effective = new WmsIdentifierPolicyEntity();
        effective.setId(17L);
        effective.setBatchRequired(true);
        effective.setBarcodeRequired(false);
        effective.setSerialManaged(false);
        when(identifiers.resolveEffective("purchase", "MAT-1")).thenReturn(effective);

        var result = service.preview("W1", "purchase", true, "MAT-1");

        assertEquals("FACTORY_POLICY", result.identifier().source());
        assertEquals("17", result.identifier().ruleId());
        assertTrue(result.identifier().batchRequired());
        assertFalse(result.identifier().barcodeRequired());
    }

    @Test
    void productionOutboundIncludesActualReservationDecision() {
        when(permissions.hasPermission("wms_wmsWarehouseMenu_view")).thenReturn(true);
        when(permissions.hasPermission("wms_reservationMovementPolicy_view")).thenReturn(true);
        when(operations.evaluate("W1", "produce", false))
                .thenReturn(new WmsWarehouseBusinessGuard.Evaluation(true, "WAREHOUSE_CONFIGURATION",
                        "/wms/outBiz/producePicking", "已开放"));
        when(reservations.preview("produce"))
                .thenReturn(new WmsReservationMovementPolicyService.Evaluation(false, "FACTORY_POLICY", 25L,
                        "当前工厂禁止新建预留"));

        var result = service.preview("W1", "produce", false, null);

        assertTrue(result.operation().allowed());
        assertEquals("EVALUATED", result.reservation().status());
        assertFalse(result.reservation().allowed());
        assertEquals("25", result.reservation().ruleId());
        assertEquals("NOT_APPLICABLE", result.identifier().status());
    }

    @Test
    void missingMenuPermissionFailsBeforeAnyRuleRead() {
        assertThrows(CheckedException.class, () -> service.preview("W1", "purchase", true, "MAT-1"));
        verifyZeroInteractions(operations, identifiers, reservations);
    }
}
