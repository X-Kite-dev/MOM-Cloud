package com.kitex.mom.wms.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanEntity;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class WmsInventoryMobileAccessTest {

	private final WmsInventoryPlanAccess records = mock(WmsInventoryPlanAccess.class);
	private final WmsInventoryWarehousePermission permissions = mock(WmsInventoryWarehousePermission.class);
	private final WmsWarehouseBusinessGuard guard = mock(WmsWarehouseBusinessGuard.class);
	private final WmsInventoryMobileAccess mobile = new WmsInventoryMobileAccess(records, permissions, guard);

	@BeforeEach
	void identity() {
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
		var user = mock(MomUser.class);
		when(user.getUsername()).thenReturn("operator");
		when(user.getTenantId()).thenReturn(1L);
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	@Test
	void contextUsesActualIdentityAndOnlyReportsExplicitlyEnabledWarehouse() {
		when(guard.mobileEnabled("W1")).thenReturn(true);

		var result = mobile.context("W1");

		assertEquals(1, result.mobileContractVersion());
		assertEquals(1L, result.tenantId());
		assertEquals("F1", result.factoryCode());
		assertEquals("operator", result.operatorCode());
		assertTrue(result.mobileEnabled());
		verify(permissions).require("add", "W1");
	}

	@Test
	void unavailableCatalogDisablesNewWorkButPermissionFailureIsNeverSwallowed() {
		when(guard.mobileEnabled("W1")).thenThrow(new IllegalStateException("catalog down"));

		assertFalse(mobile.context("W1").mobileEnabled());
		doThrow(new CheckedException("not authorized")).when(permissions).require("add", "W1");
		assertThrows(CheckedException.class, () -> mobile.context("W1"));
	}

	@Test
	void skippedContextAndPermissionWildcardsCannotBecomeAnActualWarehouse() {
		assertThrows(CheckedException.class, () -> mobile.context("*"));
		FactoryContextHolder.setFactorySkip();
		assertThrows(CheckedException.class, () -> mobile.context("W1"));
		verify(guard, never()).mobileEnabled(anyString());
	}

	@Test
	void detailPermissionUsesTheOriginalPlanAndRejectsMismatchedWarehouseRecords() {
		var row = new WmsInsideInventoryPlanDetailEntity();
		row.setPlanId(1L);
		row.setWarehouseCode("W2");
		var plan = new WmsInsideInventoryPlanEntity();
		plan.setWarehouseCode("W1");
		when(records.detail(2L)).thenReturn(row);
		when(records.plan(1L, false)).thenReturn(plan);

		assertThrows(CheckedException.class, () -> mobile.detail(2L, "hand"));
		verify(permissions, never()).require(anyString(), anyString());
		row.setWarehouseCode("W1");
		assertSame(plan, mobile.detail(2L, "hand"));
		verify(permissions).require("hand", "W1");
	}
}
