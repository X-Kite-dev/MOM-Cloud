package com.kitex.mom.common.metrics;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** 精确比率基础不依赖生产业务，覆盖数量边界与证据状态。 */
class MetricFractionTest {
	@Test
	void knownZeroNumeratorRemainsAnActualZeroPercentage() {
		var result = fraction("0", "10", true);

		assertEquals("CALCULABLE", result.status());
		assertEquals("0.0000", result.percentage());
		assertEquals("NONE", result.reason());
	}

	@Test
	void completePopulationIsExactlyOneHundredPercent() {
		assertEquals("100.0000", fraction("17", "17", true).percentage());
	}

	@Test
	void nonMesDevicePopulationUsesTheSameExactArithmetic() {
		var result = fraction("2", "3", true);

		assertEquals("66.6667", result.percentage());
		assertEquals("2", result.numerator());
		assertEquals("3", result.denominator());
	}

	@Test
	void halfUpRoundingIsExplicitAtFourPercentageDecimals() {
		assertEquals("0.0001", fraction("1", "2000000", true).percentage());
	}

	@Test
	void missingPopulationIsNotAZeroOrHealthyPercentage() {
		var result = fraction("0", "0", true);

		assertEquals("NOT_APPLICABLE", result.status());
		assertEquals("ZERO_DENOMINATOR", result.reason());
		assertNull(result.percentage());
	}

	@Test
	void incompleteZeroPopulationCannotClaimNotApplicable() {
		var result = fraction("0", "0", false);

		assertEquals("INCOMPLETE", result.status());
		assertEquals("MISSING_EVIDENCE", result.reason());
		assertNull(result.percentage());
	}

	@Test
	void incompleteEvidencePreservesKnownCountsWithoutComputingPercentage() {
		var result = fraction("3", "7", false);

		assertEquals("INCOMPLETE", result.status());
		assertEquals("3", result.numerator());
		assertEquals("7", result.denominator());
		assertNull(result.percentage());
	}

	@Test
	void missingNumeratorIsNotZero() {
		var result = MetricFraction.bounded(null, BigDecimal.TEN, true);

		assertEquals("INCOMPLETE", result.status());
		assertNull(result.numerator());
		assertEquals("10", result.denominator());
		assertNull(result.percentage());
	}

	@Test
	void missingDenominatorIsNotZero() {
		var result = MetricFraction.bounded(BigDecimal.ONE, null, true);

		assertEquals("INCOMPLETE", result.status());
		assertNull(result.denominator());
		assertNull(result.percentage());
	}

	@Test
	void negativeNumeratorIsInvalidEvenIfEvidenceIsIncomplete() {
		assertEquals("INVALID", fraction("-1", "10", false).status());
	}

	@Test
	void negativeDenominatorIsInvalid() {
		assertEquals("INVALID", fraction("0", "-10", true).status());
	}

	@Test
	void numeratorAboveDenominatorIsInvalidInsteadOfClamped() {
		var result = fraction("11", "10", true);

		assertEquals("INVALID", result.status());
		assertEquals("INVALID_FRACTION", result.reason());
		assertEquals("11", result.numerator());
		assertNull(result.percentage());
	}

	@Test
	void nonzeroNumeratorOverZeroDenominatorIsInvalid() {
		assertEquals("INVALID", fraction("1", "0", true).status());
	}

	@Test
	void valuesAboveJavascriptSafeIntegerRemainExactText() {
		var result = fraction("9007199254740993", "18014398509481986", true);

		assertEquals("9007199254740993", result.numerator());
		assertEquals("18014398509481986", result.denominator());
		assertEquals("50.0000", result.percentage());
	}

	@Test
	void decimalSourceValuesRemainPrecise() {
		var result = fraction("0.1", "0.3", true);

		assertEquals("33.3333", result.percentage());
		assertEquals("0.1", result.numerator());
	}

	@Test
	void exponentInputsAreReturnedAsPlainNormalizedText() {
		var result = fraction("1E+8", "2E+8", true);

		assertEquals("100000000", result.numerator());
		assertEquals("200000000", result.denominator());
		assertEquals("50.0000", result.percentage());
	}

	private MetricFraction.Result fraction(String numerator, String denominator, boolean complete) {
		return MetricFraction.bounded(new BigDecimal(numerator), new BigDecimal(denominator), complete);
	}
}
