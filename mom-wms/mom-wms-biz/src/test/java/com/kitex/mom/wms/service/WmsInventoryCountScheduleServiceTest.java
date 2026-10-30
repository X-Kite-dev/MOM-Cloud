package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WmsInventoryCountScheduleServiceTest {
	@Test
	void nextDueUsesTheConfiguredLocalClock() {
		assertEquals(LocalDateTime.parse("2026-10-27T02:00:00"),
				WmsInventoryCountScheduleService.nextDueAfter(
						LocalDateTime.parse("2026-10-27T01:00:00"), 1, "10:00", "Asia/Shanghai"));
		assertEquals(LocalDateTime.parse("2026-10-28T02:00:00"),
				WmsInventoryCountScheduleService.nextDueAfter(
						LocalDateTime.parse("2026-10-27T03:00:00"), 1, "10:00", "Asia/Shanghai"));
	}

	@Test
	void nextDueRetainsLocalTimeAcrossDaylightSavingAndSkipsMissedSlots() {
		assertEquals(LocalDateTime.parse("2026-03-08T13:00:00"),
				WmsInventoryCountScheduleService.nextDueFromSlot(
						LocalDateTime.parse("2026-03-07T14:00:00"),
						LocalDateTime.parse("2026-03-07T14:30:00"), 1, "09:00", "America/New_York"));
		assertEquals(LocalDateTime.parse("2026-03-11T13:00:00"),
				WmsInventoryCountScheduleService.nextDueFromSlot(
						LocalDateTime.parse("2026-03-07T14:00:00"),
						LocalDateTime.parse("2026-03-10T15:00:00"), 1, "09:00", "America/New_York"));
	}

	@Test
	void invalidScheduleTimeAndZoneFailClosed() {
		assertThrows(CheckedException.class, () -> WmsInventoryCountScheduleService.nextDueAfter(
				LocalDateTime.parse("2026-10-27T01:00:00"), 0, "10:00", "Asia/Shanghai"));
		assertThrows(CheckedException.class, () -> WmsInventoryCountScheduleService.nextDueAfter(
				LocalDateTime.parse("2026-10-27T01:00:00"), 1, "25:00", "Asia/Shanghai"));
		assertThrows(CheckedException.class, () -> WmsInventoryCountScheduleService.nextDueAfter(
				LocalDateTime.parse("2026-10-27T01:00:00"), 1, "10:00", "unknown-zone"));
	}
}
