package com.kitex.mom.wms.service.overreturn;

import com.kitex.common.core.exception.CheckedException;

import java.math.BigDecimal;

/**
 * 返还的精确数量与标识规则；拒绝隐式舍入、指数和空组织。
 */
public final class WmsOverReturnRules {
	private WmsOverReturnRules() {
	}

	/**
	 * 业务约束失败时明确拒绝整笔事务。
	 */
	public static void require(boolean valid, String message) {
		if (!valid) {
			throw new CheckedException(message);
		}
	}

	/**
	 * 解析规范正ID，拒超Long范围和数字变形。
	 */
	public static long id(String value) {
		require(value != null && value.matches("[1-9][0-9]{0,18}"), "实物身份必须为规范正ID");
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException e) {
			throw new CheckedException("实物身份超出范围");
		}
	}

	/**
	 * 将内部十进制投影成无指数无多余尾零文本。
	 */
	public static String decimal(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}

	/**
	 * 验证原单位正数量，禁止numeric20,6舍入及Double损失。
	 */
	public static BigDecimal quantity(String value) {
		require(value != null && value.matches("(?:0|[1-9][0-9]{0,13})(?:\\.[0-9]{1,6})?"), "数量须为20,6正十进制文本");
		BigDecimal q = new BigDecimal(value);
		require(q.signum() > 0 && decimal(q).equals(value) && BigDecimal.valueOf(q.doubleValue()).compareTo(q) == 0,
				"数量必须规范且可无损往返库存数量");
		return q;
	}

	/**
	 * 公共事件时间始终含秒，只允许数据库可持久化的微秒精度。
	 */
	public static String eventTime(java.time.LocalDateTime time) {
		if (time == null) {
			return null;
		}
		return new java.time.format.DateTimeFormatterBuilder().appendPattern("uuuu-MM-dd'T'HH:mm:ss")
				.appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 0, 6, true).toFormatter().format(time);
	}

	/**
	 * 本域业务规则处理。
	 */
	public static void text(String value, int max, String label) {
		require(value != null && !value.isBlank() && value.length() <= max && value.indexOf(0) < 0,
				label + "为空、过长或含无效字符");
	}
}
