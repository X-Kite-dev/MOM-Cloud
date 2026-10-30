package com.kitex.mom.common.metrics;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 无业务依赖的有界精确比率；缺证据与零分母不能表示成健康百分比。 */
public final class MetricFraction {
	private static final BigDecimal HUNDRED = new BigDecimal("100");

	private MetricFraction() {
	}

	/**
	 * 保留已知原值，只有完整且合法的0至1比例才计算百分比。
	 * 百分比按四位小数HALF_UP舍入，不先舍入分数或转换浮点数。
	 */
	public static Result bounded(BigDecimal numerator, BigDecimal denominator, boolean evidenceComplete) {
		String n = text(numerator);
		String d = text(denominator);
		if (numerator != null && numerator.signum() < 0
				|| denominator != null && denominator.signum() < 0
				|| numerator != null && denominator != null && numerator.compareTo(denominator) > 0) {
			return new Result("INVALID", n, d, null, "INVALID_FRACTION");
		}
		if (!evidenceComplete || numerator == null || denominator == null) {
			return new Result("INCOMPLETE", n, d, null, "MISSING_EVIDENCE");
		}
		if (denominator.signum() == 0) {
			return new Result("NOT_APPLICABLE", n, d, null, "ZERO_DENOMINATOR");
		}
		String percentage = numerator.multiply(HUNDRED).divide(denominator, 4, RoundingMode.HALF_UP).toPlainString();
		return new Result("CALCULABLE", n, d, percentage, "NONE");
	}

	private static String text(BigDecimal value) {
		return value == null ? null : value.stripTrailingZeros().toPlainString();
	}

	/** 数量和百分比按精确文本传递，避免前端大数精度损失。 */
	public record Result(String status, String numerator, String denominator, String percentage, String reason) {
	}
}
