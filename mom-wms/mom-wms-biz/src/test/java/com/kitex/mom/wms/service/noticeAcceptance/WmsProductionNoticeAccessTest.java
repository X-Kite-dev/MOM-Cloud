package com.kitex.mom.wms.service.noticeAcceptance;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.core.constant.SecurityConstants;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WmsProductionNoticeAccessTest {
    final PermissionService permissions = mock(PermissionService.class);
    final RemoteMdmFactoryAccessService factories = mock(RemoteMdmFactoryAccessService.class);
    final WmsProductionNoticeAccess access = new WmsProductionNoticeAccess(permissions, factories);

    @BeforeEach
    void setup() {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var user = mock(MomUser.class);
        when(user.getId()).thenReturn(7L);
        when(user.getTenantId()).thenReturn(1L);
        when(user.getUsername()).thenReturn("operator");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        when(factories.check(any())).thenReturn(R.ok(true));
    }

    void internal() {
        var request = new MockHttpServletRequest();
        request.addHeader(SecurityConstants.FROM, SecurityConstants.FROM_IN);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void sourceMutationRequiresInternalMarkerAndActualMesWritePermissionTogether() {
        when(permissions.hasPermission("mes_mesWarehousePickingBill_edit")).thenReturn(true);
        assertThrows(CheckedException.class, () -> access.scope("W1", true, false));
        internal();
        assertEquals("F1", access.scope("W1", true, false).factory());
        when(factories.check(any())).thenReturn(R.ok(false));
        assertThrows(CheckedException.class, () -> access.scope("W1", true, false));
    }

    @Test
    void warehouseReaderCannotCancelWholeSourceOrQueryAnotherWarehouse() {
        when(permissions.hasPermission("wms_notice_view_produce", "wms_notice_view_produce_W1")).thenReturn(true);
        assertEquals("operator", access.scope("W1", false, false).operator());
        assertThrows(CheckedException.class, () -> access.scope("W2", false, false));
        assertThrows(CheckedException.class, () -> access.scope("", true, true));
        assertThrows(CheckedException.class, () -> access.scope("*", false, false));
    }

    @Test
    void scopeBypassIsRejectedEvenForAnInternalMesEditor() {
        internal();
        when(permissions.hasPermission("mes_mesWarehousePickingBill_edit")).thenReturn(true);
        FactoryContextHolder.setFactorySkip();
        assertThrows(CheckedException.class, () -> access.scope("W1", true, false));
        FactoryContextHolder.clear();
        FactoryContextHolder.setFactoryCode("F1");
        TenantContextHolder.setTenantSkip();
        assertThrows(CheckedException.class, () -> access.scope("W1", true, false));
    }
}
