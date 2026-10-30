package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesWipAgingDTO.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** 验证MES计数与证据约束，精确历时运算另由公共ElapsedDuration测试。 */
class MesWipAgingCalculatorTest {

	private final MesWipAgingCalculator calculator = new MesWipAgingCalculator();
	private final LocalDateTime observed = LocalDateTime.parse("2026-10-23T12:00:00.123456");

	@Test
	void countsBeyondJavaScriptSafeIntegerKeepExactTextAndFraction() {
		var a = counts().setPopulationCount("9007199254740993").setWaitingDispatchCount("9007199254740993")
				.setKnownQueueAgeCount("9007199254740993").setOverThresholdCount("9007199254740992")
				.setOldestNoticeTime(observed.minusDays(2));
		var s = calculator.summary(a, observed, false);

		assertEquals("9007199254740993", s.getPopulationCount());
		assertEquals("9007199254740992", s.getOverThresholdRate().numerator());
		assertEquals("9007199254740993", s.getOverThresholdRate().denominator());
		assertEquals("100.0000", s.getOverThresholdRate().percentage());
	}

	@Test
	void workshopCoverageWithZeroSelectedPopulationRemainsIncomplete() {
		var q = new Query().setWorkshopCode("W1");
		var coverage = new Coverage().setUnattributedCount("1");
		var s = calculator.summary(counts(), observed, calculator.coverageIncomplete(q, coverage));

		assertEquals("INCOMPLETE", s.getOverThresholdRate().status());
		assertNull(s.getOverThresholdRate().percentage());
		assertFalse(calculator.coverageIncomplete(new Query(), coverage));
	}

	@Test
	void contradictoryCountsAndMissingKnownSubsetEvidenceAreRejected() {
		assertThrows(CheckedException.class, () -> calculator.summary(counts().setPopulationCount("1"), observed, false));
		assertThrows(CheckedException.class, () -> calculator.summary(counts().setOverThresholdCount("1"), observed, false));
		assertThrows(CheckedException.class, () -> calculator.summary(counts().setPopulationCount("1").setWaitingDispatchCount("1")
				.setKnownQueueAgeCount("1"), observed, false));
		assertThrows(CheckedException.class, () -> calculator.summary(counts().setPopulationCount(null), observed, false));
	}

	private Aggregate counts() {
		return new Aggregate().setPopulationCount("0").setWaitingDispatchCount("0").setWaitingReceiveCount("0")
				.setReceivedCount("0").setProducingCount("0").setPausedCount("0").setUnknownStatusCount("0")
				.setSourceIssueCount("0").setKnownQueueAgeCount("0").setUnknownQueueAgeCount("0").setOverThresholdCount("0");
	}
}
