package com.kitex.mom.wms.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** 只根据预警记录自身快照计算；不读取今日配置或客户端时钟。 */
public final class WmsMaterialWarningTiming {
	private WmsMaterialWarningTiming() {
	}

	public static String deadline(String type, LocalDate producedAt, LocalDateTime enteredAt, Integer days) {
		if (days == null || days < 0) {
			return null;
		}
		try {
			if ("0".equals(type) && producedAt != null) {
				return producedAt.plusDays(days).toString();
			}
			if ("1".equals(type) && enteredAt != null) {
				return enteredAt.plusDays(days).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
			}
		} catch (java.time.DateTimeException ignored) {
			/* 旧记录参数无法计算，保持未知。 */ }
		return null;
	}

	public static Long excessDays(String type, LocalDate producedAt, LocalDateTime enteredAt,
			Integer days, LocalDateTime observedAt) {
		if (days == null || days < 0 || observedAt == null) {
			return null;
		}
		try {
			if ("0".equals(type) && producedAt != null) {
				LocalDate deadline = producedAt.plusDays(days);
				return observedAt.toLocalDate().isAfter(deadline)
						? ChronoUnit.DAYS.between(deadline, observedAt.toLocalDate())
						: null;
			}
			if ("1".equals(type) && enteredAt != null) {
				LocalDateTime deadline = enteredAt.plusDays(days);
				return observedAt.isAfter(deadline) ? ChronoUnit.DAYS.between(deadline, observedAt) : null;
			}
		} catch (java.time.DateTimeException ignored) {
			/* 无法核算旧记录时不补成0。 */ }
		return null;
	}
}
