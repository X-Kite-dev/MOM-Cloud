package com.kitex.mom.wms.controller;

import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.wms.dto.WmsInsideInventoryPlanQueryDTO;
import com.kitex.mom.wms.dto.WmsInventoryAdjustmentCommand;
import com.kitex.mom.wms.dto.WmsInventoryCancelCommand;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanEntity;
import com.kitex.mom.wms.service.WmsInsideInventoryPlanService;
import com.kitex.mom.wms.service.impl.WmsInventoryAdjustmentService;
import com.kitex.mom.wms.service.impl.WmsInventoryCancellationService;
import com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess;
import com.kitex.mom.wms.service.impl.WmsInventoryRecountService;
import com.kitex.mom.wms.service.impl.WmsInventoryWarehousePermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsInventoryWarehouseAccessTest {
    private final WmsInsideInventoryPlanService plans = mock(WmsInsideInventoryPlanService.class);
    private final WmsInventoryAdjustmentService adjustments = mock(WmsInventoryAdjustmentService.class);
    private final WmsInventoryCancellationService cancellations = mock(WmsInventoryCancellationService.class);
    private final WmsInventoryRecountService recounts = mock(WmsInventoryRecountService.class);
    private final WmsInventoryPlanAccess access = mock(WmsInventoryPlanAccess.class);

    @AfterEach
    void clearScope() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void scopedRoleCannotPageOrCountWithoutAnAuthorizedWarehouse() {
        authenticate("wms_wmsInsideInventoryPlan_view_W1");
        var query = new WmsInsideInventoryPlanQueryDTO();

        assertThrows(CheckedException.class, () -> controller().getWmsInsideInventoryPlanPage(new SearchPage(), query));
        assertThrows(CheckedException.class, () -> controller().getCount(query));

        query.setWarehouseCode("W2");
        assertThrows(CheckedException.class, () -> controller().getWmsInsideInventoryPlanPage(new SearchPage(), query));
        verify(plans, never()).page(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void scopedRoleCannotOpenOrAdjustAnotherWarehousePlan() {
        authenticate("wms_wmsInsideInventoryPlan_view_W1", "wms_wmsInsideInventoryPlan_hand_W1");
        var plan = new WmsInsideInventoryPlanEntity();
        plan.setId(1L);
        plan.setWarehouseCode("W2");
        var detail = new WmsInsideInventoryPlanDetailEntity();
        detail.setPlanId(1L);
        when(access.plan(1L, false)).thenReturn(plan);
        when(access.detail(2L)).thenReturn(detail);

        assertThrows(CheckedException.class, () -> controller().getById(1L));
        var command = new WmsInventoryAdjustmentCommand();
        command.setPlanDetailId(2L);
        assertThrows(CheckedException.class, () -> controller().adjust(command));

        verify(plans, never()).getDetails(1L);
        verify(adjustments, never()).adjust(command);
    }

    @Test
    void addOnlyRoleCanCheckNumberOnlyForItsWarehouse() {
        authenticate("wms_wmsInsideInventoryPlan_add_W1");

        assertThrows(CheckedException.class, () -> controller().exists("PD-1", "W2"));
        verify(plans, never()).count(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void scopedCancelRoleCannotCancelAnotherWarehousePlan() {
        authenticate("wms_wmsInsideInventoryPlan_cancel_W1");
        var plan = new WmsInsideInventoryPlanEntity();
        plan.setWarehouseCode("W2");
        when(access.plan(1L, false)).thenReturn(plan);
        var command = new WmsInventoryCancelCommand();
        command.setId(1L);

        assertThrows(CheckedException.class, () -> controller().cancel(command));

        verify(cancellations, never()).cancel(command);
    }

    private WmsInsideInventoryPlanController controller() {
        return new WmsInsideInventoryPlanController(plans, adjustments,
                mock(com.kitex.mom.wms.service.impl.WmsInventoryAdjustmentApprovalService.class), cancellations, recounts,
                access, new WmsInventoryWarehousePermission(new PermissionService()));
    }

    private void authenticate(String... codes) {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var authorities = java.util.Arrays.stream(codes).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("operator", null, authorities));
    }
}
