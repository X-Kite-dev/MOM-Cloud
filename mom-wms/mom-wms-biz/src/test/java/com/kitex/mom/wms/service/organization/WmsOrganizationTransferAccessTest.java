package com.kitex.mom.wms.service.organization;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WmsOrganizationTransferAccessTest {
	PermissionService permissions = mock(PermissionService.class);
	RemoteMdmFactoryAccessService factories = mock(RemoteMdmFactoryAccessService.class);
	WmsWarehouseBusinessGuard business = mock(WmsWarehouseBusinessGuard.class);
	WmsOrganizationTransferAccess access = new WmsOrganizationTransferAccess(permissions, factories,
			mock(RemoteWarehouseService.class), business);

	@BeforeEach
	void identity() {
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
		var user = mock(MomUser.class);
		when(user.getId()).thenReturn(7L);
		when(user.getTenantId()).thenReturn(1L);
		when(user.getUsername()).thenReturn("source");
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(user, null, List.of()));
		when(permissions.hasPermission(anyString(), anyString())).thenReturn(true);
		when(factories.check(any())).thenReturn(R.ok(true));
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	@Test
	void mobileAdmissionUsesTheExistingActualWarehouseConfiguration() {
		var scope = access.scope("WA", true);
		when(business.mobileEnabled("WA")).thenReturn(true);

		assertTrue(access.mobileEnabled(scope));
		access.checkMobile(scope);

		verify(business).checkMobile("WA");
		doThrow(new CheckedException("mobile disabled")).when(business).checkMobile("WA");
		assertThrows(CheckedException.class, () -> access.checkMobile(scope));
	}

	@Test
	void verifiesActualFactoryBindingRatherThanTrustingAFactoryHeaderAlone() {
		assertEquals("F1", access.scope("WA", true).factory());
		when(factories.check(any())).thenReturn(R.ok(false));
		assertThrows(CheckedException.class, () -> access.scope("WA", true));
	}

	@Test
	void rejectsTenantFactoryBypassWildcardsAndMissingWarehousePermissions() {
		assertThrows(CheckedException.class, () -> access.scope("*", true));
		FactoryContextHolder.setFactorySkip();
		assertThrows(CheckedException.class, () -> access.scope("WA", true));
		FactoryContextHolder.clear();
		FactoryContextHolder.setFactoryCode("F1");
		TenantContextHolder.setTenantSkip();
		assertThrows(CheckedException.class, () -> access.scope("WA", true));
		TenantContextHolder.clear();
		TenantContextHolder.setTenantId(1L);
		when(permissions.hasPermission(anyString(), anyString())).thenReturn(false);
		assertThrows(CheckedException.class, () -> access.scope("WA", true));
	}
}
