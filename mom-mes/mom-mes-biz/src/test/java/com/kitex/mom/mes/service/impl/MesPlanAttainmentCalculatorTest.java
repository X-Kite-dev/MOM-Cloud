package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesPlanAttainmentDTO.Aggregate;
import com.kitex.mom.mes.dto.MesPlanAttainmentDTO.Query;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 公共精确比率接入MES分类后的分母和完整性回归。 */
class MesPlanAttainmentCalculatorTest {

	private final MesPlanAttainmentCalculator calculator = new MesPlanAttainmentCalculator();

	@Test
	void allClosedObjectsAreUnfinishedByDefault() {
		var summary = calculator.summary(counts("3", "0", "0", "0", "3", "0", "0"), new Query(), false);

		assertEquals("3", summary.getApplicableCount());
		assertEquals("0", summary.getCompletedCount());
		assertEquals("CALCULABLE", summary.getCompletionRate().status());
		assertEquals("0.0000", summary.getCompletionRate().percentage());
	}

	@Test
	void explicitlyExcludingEveryClosedObjectLeavesNoApplicablePopulation() {
		var query = new Query().setClosedPolicy("EXCLUDE_CLOSED");
		var summary = calculator.summary(counts("3", "0", "0", "0", "3", "0", "0"), query, false);

		assertEquals("3", summary.getDueCount());
		assertEquals("3", summary.getClosedCount());
		assertEquals("0", summary.getApplicableCount());
		assertEquals("NOT_APPLICABLE", summary.getCompletionRate().status());
		assertNull(summary.getCompletionRate().percentage());
	}

	@Test
	void draftsStayVisibleButDoNotBecomeApplicablePlans() {
		var summary = calculator.summary(counts("5", "0", "0", "0", "0", "5", "0"), new Query(), false);

		assertEquals("5", summary.getDraftCount());
		assertEquals("0", summary.getApplicableCount());
		assertEquals("ZERO_DENOMINATOR", summary.getOnTimeRate().reason());
	}

	@Test
	void knownObjectCountsBeyondJavascriptPrecisionRemainExactAcrossBothRatios() {
		var summary = calculator.summary(counts("18014398509481986", "9007199254740993", "0",
				"9007199254740993", "0", "0", "0"), new Query(), false);

		assertEquals("18014398509481986", summary.getApplicableCount());
		assertEquals("9007199254740993", summary.getCompletedCount());
		assertEquals("50.0000", summary.getCompletionRate().percentage());
		assertEquals("50.0000", summary.getOnTimeRate().percentage());
	}

	@Test
	void unknownOrCoverageGapSuppressesBothPercentagesWithoutDiscardingKnownCounts() {
		var unknown = calculator.summary(counts("2", "1", "0", "0", "0", "0", "1"), new Query(), false);
		var uncovered = calculator.summary(counts("1", "1", "0", "0", "0", "0", "0"), new Query(), true);

		assertEquals("1", unknown.getCompletionRate().numerator());
		assertEquals("2", unknown.getCompletionRate().denominator());
		assertEquals("INCOMPLETE", unknown.getCompletionRate().status());
		assertEquals("INCOMPLETE", unknown.getOnTimeRate().status());
		assertEquals("1", uncovered.getApplicableCount());
		assertNull(uncovered.getCompletionRate().percentage());
		assertNull(uncovered.getOnTimeRate().percentage());
	}

	@Test
	void missingNegativeOrInconsistentAggregatesAreNotReplacedByZeros() {
		assertThrows(CheckedException.class, () -> calculator.summary(new Aggregate(), new Query(), false));
		assertThrows(CheckedException.class, () -> calculator.summary(counts("1", "-1", "0", "0", "0", "0", "0"), new Query(), false));
		assertThrows(CheckedException.class, () -> calculator.summary(counts("1", "0", "0", "2", "0", "0", "0"), new Query(), false));
	}

	private Aggregate counts(String due, String onTime, String late, String open, String closed, String draft, String unknown) {
		return new Aggregate().setDueCount(due).setOnTimeCount(onTime).setLateCount(late).setOpenCount(open)
				.setClosedCount(closed).setDraftCount(draft).setUnknownCount(unknown);
	}
}
