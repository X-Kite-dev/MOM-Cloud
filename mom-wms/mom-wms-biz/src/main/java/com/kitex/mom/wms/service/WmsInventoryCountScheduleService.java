package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

/** 仓库盘点计划的时间计算统一采用模板时区，数据库中的到期时刻采用 UTC。 */
public final class WmsInventoryCountScheduleService {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm")
			.withResolverStyle(ResolverStyle.STRICT);

	private WmsInventoryCountScheduleService() {
	}

	public static LocalDateTime nextDueAfter(LocalDateTime nowUtc, int intervalDays,
			String timeOfDay, String zoneId) {
		ScheduleClock clock = validate(nowUtc, intervalDays, timeOfDay, zoneId);
		LocalDate startDate = nowUtc.toInstant(ZoneOffset.UTC).atZone(clock.zone()).toLocalDate();
		return firstDueAfter(startDate, nowUtc, clock);
	}

	public static LocalDateTime nextDueFromSlot(LocalDateTime dueUtc, LocalDateTime nowUtc,
			int intervalDays, String timeOfDay, String zoneId) {
		ScheduleClock clock = validate(nowUtc, intervalDays, timeOfDay, zoneId);
		if (dueUtc == null) {
			throw new CheckedException("盘点任务到期时刻不能为空");
		}
		LocalDate nextDate = dueUtc.toInstant(ZoneOffset.UTC).atZone(clock.zone())
				.toLocalDate().plusDays(intervalDays);
		return firstDueAfter(nextDate, nowUtc, clock);
	}

	private static LocalDateTime firstDueAfter(LocalDate startDate, LocalDateTime nowUtc,
			ScheduleClock clock) {
		Instant now = nowUtc.toInstant(ZoneOffset.UTC);
		LocalDate date = startDate;
		for (int attempts = 0; attempts < 100000; attempts++) {
			Instant due = date.atTime(clock.time()).atZone(clock.zone()).toInstant();
			if (due.isAfter(now)) {
				return LocalDateTime.ofInstant(due, ZoneOffset.UTC);
			}
			date = date.plusDays(clock.intervalDays());
		}
		throw new CheckedException("盘点任务下次执行时间计算超限");
	}

	private static ScheduleClock validate(LocalDateTime nowUtc, int intervalDays,
			String timeOfDay, String zoneId) {
		if (nowUtc == null || intervalDays < 1 || intervalDays > 365
				|| timeOfDay == null || !timeOfDay.matches("[0-2][0-9]:[0-5][0-9]")
				|| zoneId == null || zoneId.length() > 64) {
			throw new CheckedException("盘点任务周期、时间或时区无效");
		}
		try {
			return new ScheduleClock(intervalDays, LocalTime.parse(timeOfDay, TIME), ZoneId.of(zoneId));
		} catch (DateTimeException exception) {
			throw new CheckedException("盘点任务时间或时区无效");
		}
	}

	private record ScheduleClock(int intervalDays, LocalTime time, ZoneId zone) {
	}
}
