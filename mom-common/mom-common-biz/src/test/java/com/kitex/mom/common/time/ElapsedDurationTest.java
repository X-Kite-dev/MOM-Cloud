package com.kitex.mom.common.time;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 不依赖MES的自然历时计算，保留时点缺失与倒置的区别。 */
class ElapsedDurationTest {

	@Test
	void exactSameInstantIsKnownZero() {
		var time = LocalDateTime.parse("2026-10-23T08:00:00");
		var result = ElapsedDuration.since(time, time);

		assertEquals("CALCULABLE", result.status());
		assertEquals("0", result.seconds());
		assertEquals("NONE", result.reason());
	}

	@Test
	void microsecondsRemainExactDecimalSeconds() {
		var result = elapsed("2026-10-23T08:00:00.123456", "2026-10-23T08:00:01.123457");

		assertEquals("1.000001", result.seconds());
	}

	@Test
	void nanosecondsAreNotRoundedOrWrittenInExponentNotation() {
		assertEquals("0.000000001", elapsed("2026-10-23T08:00:00", "2026-10-23T08:00:00.000000001").seconds());
	}

	@Test
	void crossingMidnightPreservesSubsecondBorrow() {
		assertEquals("0.5", elapsed("2026-10-22T23:59:59.750", "2026-10-23T00:00:00.250").seconds());
	}

	@Test
	void nonManufacturingSupportCaseCanUseTheSameNaturalElapsedTime() {
		assertEquals("90000", elapsed("2026-10-22T07:00:00", "2026-10-23T08:00:00").seconds());
	}

	@Test
	void elapsedSecondsBeyondJavascriptPrecisionRemainExactText() {
		var origin = LocalDateTime.of(-300_000_000, 1, 1, 0, 0);
		var observed = LocalDateTime.of(300_000_000, 1, 1, 0, 0);

		assertEquals("18934171200000000", ElapsedDuration.since(origin, observed).seconds());
	}

	@Test
	void missingOriginDoesNotBecomeZeroSeconds() {
		var result = ElapsedDuration.since(null, LocalDateTime.parse("2026-10-23T08:00:00"));

		assertEquals("INCOMPLETE", result.status());
		assertEquals("MISSING_ORIGIN", result.reason());
		assertNull(result.seconds());
	}

	@Test
	void missingObservationDoesNotUseTheCurrentMachineClock() {
		var result = ElapsedDuration.since(LocalDateTime.parse("2026-10-23T08:00:00"), null);

		assertEquals("INCOMPLETE", result.status());
		assertEquals("MISSING_OBSERVATION", result.reason());
		assertNull(result.seconds());
	}

	@Test
	void futureOriginIsInvalidInsteadOfClampedToZero() {
		var result = elapsed("2026-10-23T08:00:00.000001", "2026-10-23T08:00:00");

		assertEquals("INVALID", result.status());
		assertEquals("FUTURE_ORIGIN", result.reason());
		assertNull(result.seconds());
	}

	private ElapsedDuration.Result elapsed(String origin, String observed) {
		return ElapsedDuration.since(LocalDateTime.parse(origin), LocalDateTime.parse(observed));
	}
}
