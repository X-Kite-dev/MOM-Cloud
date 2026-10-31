package com.kitex.mom.wms.service;

import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.admin.api.feign.RemoteMenuService;
import com.kitex.admin.api.feign.RemoteUserService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.service.impl.WmsWarehouseMenuServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WmsMobileWarehouseMenuTest {

	@AfterEach
	void clear() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	@Test
	void disabledWarehouseHasAnExplicitStatusAndNoRoleMenusAreLoaded() {
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
		var menus = mock(RemoteMenuService.class);
		var biz = mock(WmsWarehouseBizService.class);
		when(biz.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of());
		when(menus.getMenuTree("wms")).thenReturn(R.ok(List.of()));
		var guard = mock(WmsWarehouseBusinessGuard.class);
		var service = new WmsWarehouseMenuServiceImpl(biz, mock(RemoteWarehouseService.class), menus,
				mock(RemoteUserService.class), guard);

		var result = service.checkBizByWarehouse("W1");

		assertFalse(result.isMobileEnabled());
		assertNotNull(result.getMobileMessage());
		assertTrue(result.getCommon().isEmpty());
		assertTrue(result.getInout().isEmpty());
		assertTrue(result.getOther().isEmpty());
		verify(menus, never()).getMenuTree(any());
	}

	@Test
	void enabledWarehouseWithoutRoleMenusIsStillReportedAsEnabled() {
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
		var menus = mock(RemoteMenuService.class);
		var biz = mock(WmsWarehouseBizService.class);
		when(biz.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of());
		when(menus.getMenuTree("wms")).thenReturn(R.ok(List.of()));
		var guard = mock(WmsWarehouseBusinessGuard.class);
		when(guard.mobileEnabled("W1")).thenReturn(true);
		var service = new WmsWarehouseMenuServiceImpl(biz, mock(RemoteWarehouseService.class), menus,
				mock(RemoteUserService.class), guard);

		var result = service.checkBizByWarehouse("W1");

		assertTrue(result.isMobileEnabled());
		assertTrue(result.getCommon().isEmpty());
	}
}
