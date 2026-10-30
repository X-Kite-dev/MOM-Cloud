package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.mapper.WmsInventoryCountScheduleMapper;
import com.kitex.mom.wms.mapper.WmsWarehouseMenuMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsInventoryCountScheduleRunServiceTest {
	private final WmsInventoryCountScheduleMapper schedules = mock(WmsInventoryCountScheduleMapper.class);
	private final WmsWarehouseMenuMapper menus = mock(WmsWarehouseMenuMapper.class);
	private final WmsInventoryCountTemplateService templates = mock(WmsInventoryCountTemplateService.class);
	private final WmsInventoryCountScheduleRunService service = new WmsInventoryCountScheduleRunService(
			schedules, menus, templates);
	private final LocalDateTime due = LocalDateTime.parse("2026-10-27T02:00:00");
	private final LocalDateTime now = LocalDateTime.parse("2026-10-27T02:01:00");
	private final WmsInventoryCountScheduleMapper.DueRow candidate =
			new WmsInventoryCountScheduleMapper.DueRow("CYCLE_A", "W1", due, 3);

	@BeforeEach
	void setScope() {
		TenantContextHolder.setTenantId(11L);
		FactoryContextHolder.setFactoryCode("F1");
	}

	@AfterEach
	void clearScope() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	@Test
	void dueSlotCreatesOnePlanAndAdvancesWithinTheSameTransaction() {
		when(schedules.findForUpdate(11L, "F1", "CYCLE_A", "W1")).thenReturn(
				new WmsInventoryCountScheduleMapper.ScheduleRow(3, true, 1, "10:00",
						"Asia/Shanghai", due));
		when(templates.triggerScheduled("W1", "CYCLE_A", due))
				.thenReturn(new WmsInventoryCountTemplateService.RunResult(901L, "PD-1", true));
		when(schedules.advance(11L, "F1", "CYCLE_A", 3, due,
				LocalDateTime.parse("2026-10-28T02:00:00"))).thenReturn(1);

		assertTrue(service.run(11L, "F1", candidate, now));

		verify(menus).lockConfiguration("WMS_WAREHOUSE_MENU:11:F1");
		verify(templates).triggerScheduled("W1", "CYCLE_A", due);
		verify(schedules).advance(11L, "F1", "CYCLE_A", 3, due,
				LocalDateTime.parse("2026-10-28T02:00:00"));
	}

	@Test
	void staleCandidateDoesNotGenerateAnotherPlan() {
		when(schedules.findForUpdate(11L, "F1", "CYCLE_A", "W1")).thenReturn(
				new WmsInventoryCountScheduleMapper.ScheduleRow(3, true, 1, "10:00",
						"Asia/Shanghai", LocalDateTime.parse("2026-10-28T02:00:00")));

		assertFalse(service.run(11L, "F1", candidate, now));

		verify(templates, never()).triggerScheduled("W1", "CYCLE_A", due);
	}

	@Test
	void creationFailureLeavesTheDueSlotForRetry() {
		when(schedules.findForUpdate(11L, "F1", "CYCLE_A", "W1")).thenReturn(
				new WmsInventoryCountScheduleMapper.ScheduleRow(3, true, 1, "10:00",
						"Asia/Shanghai", due));
		when(templates.triggerScheduled("W1", "CYCLE_A", due))
				.thenThrow(new CheckedException("盘点范围冲突"));

		assertThrows(CheckedException.class, () -> service.run(11L, "F1", candidate, now));

		verify(schedules, never()).advance(11L, "F1", "CYCLE_A", 3, due,
				LocalDateTime.parse("2026-10-28T02:00:00"));
	}

	@Test
	void mismatchedTenantContextCannotRunAJob() {
		assertThrows(CheckedException.class, () -> service.run(12L, "F1", candidate, now));
		verify(templates, never()).triggerScheduled("W1", "CYCLE_A", due);
	}
}
