package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.mapper.WmsInventoryCountScheduleMapper;
import com.kitex.mom.wms.mapper.WmsInventoryCountTemplateMapper;
import com.kitex.mom.wms.mapper.WmsWarehouseMenuMapper;
import com.kitex.mom.wms.service.impl.WmsInventoryWarehousePermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsInventoryCountScheduleConfigServiceTest {
	private final WmsInventoryCountScheduleMapper schedules = mock(WmsInventoryCountScheduleMapper.class);
	private final WmsInventoryCountTemplateMapper templates = mock(WmsInventoryCountTemplateMapper.class);
	private final WmsWarehouseMenuMapper menus = mock(WmsWarehouseMenuMapper.class);
	private final PermissionService permissions = mock(PermissionService.class);
	private final RemoteWarehouseService warehouses = mock(RemoteWarehouseService.class);
	private final WmsInventoryCountScheduleConfigService service = new WmsInventoryCountScheduleConfigService(
			schedules, templates, menus, new WmsInventoryWarehousePermission(permissions),
			permissions, warehouses);

	@BeforeEach
	void setScope() {
		TenantContextHolder.setTenantId(11L);
		FactoryContextHolder.setFactoryCode("F1");
		when(permissions.hasPermission("wms_wmsInsideInventoryPlan_add_W1")).thenReturn(true);
		when(permissions.hasPermission("wms_wmsWarehouseMenu_add")).thenReturn(true);
		when(templates.find(11L, "F1", "CYCLE_A")).thenReturn(new WmsInventoryCountTemplateMapper.TemplateRow(
				"CYCLE_A", "临期盘点", "W1", "NEAR_EXPIRY", 14, "[\"S1\"]", "SNAPSHOT", true, 3));
		MdmWarehouseEntity warehouse = new MdmWarehouseEntity()
				.setWarehouseCode("W1").setWarehouseName("原辅仓").setFactoryCode("F1").setStatus(true);
		warehouse.setTenantId(11L);
		when(warehouses.getByCode("W1")).thenReturn(R.ok(warehouse));
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
	void saveComputesNextDueAndChecksExpectedVersion() {
		LocalDateTime now = LocalDateTime.parse("2026-10-27T01:00:00");
		when(schedules.upsert(11L, "F1", "CYCLE_A", "W1", true, 1, "10:00",
				"Asia/Shanghai", LocalDateTime.parse("2026-10-27T02:00:00"), 0, "operator"))
				.thenReturn(1);
		var command = new WmsInventoryCountScheduleConfigService.SaveCommand(
				"W1", "CYCLE_A", true, 1, "10:00", "Asia/Shanghai", 0);

		var saved = service.saveAt(command, now);

		assertEquals(1, saved.version());
		assertEquals(LocalDateTime.parse("2026-10-27T02:00:00"), saved.nextRunUtc());
		verify(menus).lockConfiguration("WMS_WAREHOUSE_MENU:11:F1");
	}

	@Test
	void staleVersionAndForeignWarehouseCannotChangeSchedule() {
		when(schedules.find(11L, "F1", "CYCLE_A", "W1"))
				.thenReturn(new WmsInventoryCountScheduleMapper.ScheduleRow(2, true, 1, "10:00",
						"Asia/Shanghai", LocalDateTime.parse("2026-10-28T02:00:00")));
		var stale = new WmsInventoryCountScheduleConfigService.SaveCommand(
				"W1", "CYCLE_A", true, 1, "10:00", "Asia/Shanghai", 1);
		assertThrows(CheckedException.class, () -> service.saveAt(stale, LocalDateTime.parse("2026-10-27T01:00:00")));
		verify(schedules, never()).upsert(any(), any(), any(), any(), anyBoolean(), anyInt(),
				any(), any(), any(), anyInt(), any());

		var foreign = new WmsInventoryCountScheduleConfigService.SaveCommand(
				"W2", "CYCLE_A", true, 1, "10:00", "Asia/Shanghai", 0);
		assertThrows(CheckedException.class, () -> service.saveAt(foreign, LocalDateTime.parse("2026-10-27T01:00:00")));
	}
}
