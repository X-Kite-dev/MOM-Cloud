package com.kitex.mom.wms.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.component.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WmsInventoryWarehousePermissionTest {

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void scopedViewCannotReadAnotherWarehouseOrUnfilteredList() {
        authenticate("wms_wmsInsideInventoryPlan_view_W1");
        var permission = new WmsInventoryWarehousePermission(new PermissionService());

        assertDoesNotThrow(() -> permission.require("view", "W1"));
        assertThrows(CheckedException.class, () -> permission.require("view", "W2"));
        assertThrows(CheckedException.class, () -> permission.require("view", ""));
    }

    @Test
    void globalViewCanReadUnfilteredList() {
        authenticate("wms_wmsInsideInventoryPlan_view");

        assertDoesNotThrow(() -> new WmsInventoryWarehousePermission(new PermissionService()).require("view", null));
    }

    @Test
    void warehouseGrantDoesNotAuthorizeAnotherAction() {
        authenticate("wms_wmsInsideInventoryPlan_view_W1", "wms_wmsInsideInventoryPlan_entry_W1");
        var permission = new WmsInventoryWarehousePermission(new PermissionService());

        assertDoesNotThrow(() -> permission.require("entry", "W1"));
        assertThrows(CheckedException.class, () -> permission.require("hand", "W1"));
    }

    @Test
    void legacyGlobalEditStillAuthorizesEntryAndHandling() {
        authenticate("wms_wmsInsideInventoryPlan_edit");
        var permission = new WmsInventoryWarehousePermission(new PermissionService());

        assertDoesNotThrow(() -> permission.require("entry", "W2"));
        assertDoesNotThrow(() -> permission.require("hand", "W2"));
        assertThrows(CheckedException.class, () -> permission.require("view", "W2"));
    }

    private static void authenticate(String... authorities) {
        var grants = Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("operator", null, grants));
    }
}
