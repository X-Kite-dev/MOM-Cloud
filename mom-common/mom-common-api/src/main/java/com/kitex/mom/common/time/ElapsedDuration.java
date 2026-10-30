package com.kitex.mom.common.time;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

/** 纯自然历时基础；调用方负责将两个时点统一到相同业务时间基准。 */
public final class ElapsedDuration {

	private ElapsedDuration() {
	}

	/** 精确返回非负秒数，缺失或倒置时点不能解释为零历时。 */
	public static Result since(LocalDateTime origin, LocalDateTime observed) {
		if (origin == null) {
			return new Result("INCOMPLETE", null, "MISSING_ORIGIN");
		}
		if (observed == null) {
			return new Result("INCOMPLETE", null, "MISSING_OBSERVATION");
		}
		if (origin.isAfter(observed)) {
			return new Result("INVALID", null, "FUTURE_ORIGIN");
		}
		Duration duration = Duration.between(origin, observed);
		BigDecimal seconds = BigDecimal.valueOf(duration.getSeconds())
				.add(BigDecimal.valueOf(duration.getNano(), 9));
		return new Result("CALCULABLE", seconds.stripTrailingZeros().toPlainString(), "NONE");
	}

	/** 秒数使用精确文本，避免前端浮点转换损失原始精度。 */
	public record Result(String status, String seconds, String reason) {
	}
}
