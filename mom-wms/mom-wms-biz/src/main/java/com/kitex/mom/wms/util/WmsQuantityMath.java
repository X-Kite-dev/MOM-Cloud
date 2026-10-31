package com.kitex.mom.wms.util;

import com.kitex.common.core.exception.CheckedException;

import java.math.BigDecimal;

/** 仓储作业数量以十进制运算，避免多行小数累计时产生浮点尾差。 */
public final class WmsQuantityMath {

	private WmsQuantityMath() {
	}

	public static double add(double left, double right) {
		return calculate(left, right, false);
	}

	public static double subtract(double left, double right) {
		return calculate(left, right, true);
	}

	private static double calculate(double left, double right, boolean subtract) {
		if (!Double.isFinite(left) || !Double.isFinite(right)) {
			throw new CheckedException("仓储作业数量无效");
		}
		BigDecimal first = BigDecimal.valueOf(left);
		BigDecimal second = BigDecimal.valueOf(right);
		double result = (subtract ? first.subtract(second) : first.add(second)).doubleValue();
		if (!Double.isFinite(result)) {
			throw new CheckedException("仓储作业数量超出有效范围");
		}
		return result;
	}
}
