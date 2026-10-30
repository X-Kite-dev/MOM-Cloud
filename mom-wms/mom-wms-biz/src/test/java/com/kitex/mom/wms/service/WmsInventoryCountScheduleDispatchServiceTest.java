package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.mapper.WmsInventoryCountScheduleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsInventoryCountScheduleDispatchServiceTest {
	private final WmsInventoryCountScheduleMapper schedules = mock(WmsInventoryCountScheduleMapper.class);
	private final WmsInventoryCountScheduleRunService runner = mock(WmsInventoryCountScheduleRunService.class);
	private final WmsInventoryCountScheduleDispatchService service =
			new WmsInventoryCountScheduleDispatchService(schedules, runner);
	private final LocalDateTime now = LocalDateTime.parse("2026-10-27T02:01:00");

	@AfterEach
	void clearScope() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	@Test
	void scopedScanRestoresCallerContextAndReportsIndependentFailures() {
		TenantContextHolder.setTenantId(99L);
		FactoryContextHolder.setFactoryCode("OTHER");
		var first = new WmsInventoryCountScheduleMapper.DueRow(
				"CYCLE_A", "W1", LocalDateTime.parse("2026-10-27T02:00:00"), 3);
		var second = new WmsInventoryCountScheduleMapper.DueRow(
				"CYCLE_B", "W1", LocalDateTime.parse("2026-10-27T02:00:00"), 1);
		when(schedules.due(11L, "F1", now, 10)).thenReturn(List.of(first, second));
		when(runner.run(11L, "F1", first, now)).thenReturn(true);
		when(runner.run(11L, "F1", second, now)).thenThrow(new CheckedException("库存范围冲突"));

		var result = service.runScopeAt(11L, "F1", 10, now);

		assertEquals(2, result.scanned());
		assertEquals(1, result.created());
		assertEquals(1, result.failed());
		assertEquals(99L, TenantContextHolder.getTenantId());
		assertEquals("OTHER", FactoryContextHolder.getFactoryCode());
	}

	@Test
	void duplicateCandidateStopsBeforeAnyPlanAndRestoresScope() {
		var duplicate = new WmsInventoryCountScheduleMapper.DueRow(
				"CYCLE_A", "W1", LocalDateTime.parse("2026-10-27T02:00:00"), 3);
		when(schedules.due(11L, "F1", now, 10)).thenReturn(List.of(duplicate, duplicate));

		assertThrows(CheckedException.class, () -> service.runScopeAt(11L, "F1", 10, now));

		verify(runner, never()).run(11L, "F1", duplicate, now);
		assertEquals(null, TenantContextHolder.getTenantId());
		assertEquals(null, FactoryContextHolder.getFactoryCode());
	}

	@Test
	void missingScopeNeverQueriesDueTemplates() {
		assertThrows(CheckedException.class, () -> service.runScopeAt(null, "F1", 10, now));
		verify(schedules, never()).due(null, "F1", now, 10);
	}
}
