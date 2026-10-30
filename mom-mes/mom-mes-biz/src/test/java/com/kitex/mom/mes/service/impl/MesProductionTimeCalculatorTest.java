package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mes.dto.MesProductionTimeDTO.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MesProductionTimeCalculatorTest {

	private static final LocalDateTime START = LocalDateTime.parse("2026-10-23T08:00:00");

	@Test
	void parallelOperationsAccumulateWithoutPretendingToBeOrderElapsedTime() {
		var result = calculate(List.of(fact("1", "A1", "RUN", 0, 3600), fact("2", "A2", "RUN", 0, 3600)));

		assertEquals("7200", result.getTotals().getProcessingSeconds());
		assertNull(result.getTotals().getLaborSeconds());
		assertEquals(1, result.getGroups().size());
		assertEquals("T1", result.getGroups().get(0).getTaskCode());
	}

	@Test
	void clipsAtBothWindowBoundariesAndExcludesTouchingIntervals() {
		var result = new MesProductionTimeCalculator().calculate(query(), List.of(fact("1", "A1", "RUN", -600, 600),
				fact("2", "A1", "RUN", 6600, 7800), fact("3", "A1", "RUN", -600, 0),
				fact("4", "A1", "RUN", 7200, 7800)), START.plusHours(3));

		assertEquals("1200", result.getTotals().getProcessingSeconds());
		assertEquals(2, result.getDetailTotal());
	}

	@Test
	void overlappingPauseSourcesOnSameArrangementCountTheirUnionOnce() {
		var result = calculate(List.of(fact("1", "A1", "PAUSE", 0, 600),
				fact("2", "A1", "PAUSE", 300, 900), fact("3", "A2", "PAUSE", 0, 300)));

		assertEquals("1200", result.getTotals().getPausedSeconds());
		assertEquals(3, result.getDetailTotal());
	}

	@Test
	void unclosedRunIsVisibleButNeverAddedToCompletedProcessing() {
		var opened = fact("1", "A1", "RUN", 0, 3600).setEndTime(null).setState("OPEN");
		var result = calculate(List.of(opened));

		assertNull(result.getTotals().getProcessingSeconds());
		assertEquals("7200", result.getTotals().getOpenProcessingSeconds());
		assertEquals(1, result.getTotals().getOpenIntervalCount());
	}

	@Test
	void invalidHistoricalSourcesAreExplainedInsteadOfZeroTime() {
		var invalid = fact("1", "A1", "RUN", 0, 600).setEligible(false).setReason("LEGACY_GAP");
		var result = calculate(List.of(invalid));

		assertNull(result.getTotals().getProcessingSeconds());
		assertEquals(1, result.getTotals().getInvalidSourceCount());
		assertFalse(result.getDataGaps().isEmpty());
		assertFalse(result.getDetails().get(0).isIncluded());
	}

	@Test
	void approvedLaborRemainsSeparateFromMachineAndWorkpieceTime() {
		var result = calculate(List.of(fact("1", "A1", "RUN", 0, 600),
				fact("2", "A1", "LABOR", 0, 300).setWorkerCode("U1"),
				fact("3", "A1", "LABOR", 0, 300).setWorkerCode("U2")));

		assertEquals("600", result.getTotals().getProcessingSeconds());
		assertEquals("600", result.getTotals().getLaborSeconds());
		assertNull(result.getTotals().getWorkpieceProcessingSeconds());
	}

	@Test
	void workpieceGroupingRequiresRealIdentificationAndDoesNotDivideBatchTime() {
		var q = query().setGroupBy("WORKPIECE");
		var result = new MesProductionTimeCalculator().calculate(q, List.of(
				fact("1", "A1", "RUN", 0, 600),
				fact("2", "A1", "WORKPIECE_RUN", 0, 120).setIdentifier("SN1").setIdentifierType("SN")), START.plusHours(2));

		assertEquals(1, result.getGroups().size());
		assertEquals("SN1", result.getGroups().get(0).getIdentifier());
		assertEquals("120", result.getGroups().get(0).getMetrics().getWorkpieceProcessingSeconds());
		assertNull(result.getGroups().get(0).getMetrics().getProcessingSeconds());
	}

	@Test
	void pagingDetailsDoesNotChangeFullPopulationTotals() {
		var q = query().setDetailPage(2).setDetailSize(1);
		var result = new MesProductionTimeCalculator().calculate(q,
				List.of(fact("1", "A1", "RUN", 0, 100), fact("2", "A1", "RUN", 100, 300)), START.plusHours(2));

		assertEquals("300", result.getTotals().getProcessingSeconds());
		assertEquals(2, result.getDetailTotal());
		assertEquals(1, result.getDetails().size());
	}

	@Test
	void reversedAndFutureIntervalsNeverContributeToCompletedTime() {
		var result = calculate(List.of(fact("1", "A1", "RUN", 600, 300), fact("2", "A1", "RUN", 600, 8000)));

		assertNull(result.getTotals().getProcessingSeconds());
		assertEquals(2, result.getTotals().getInvalidSourceCount());
	}

	@Test
	void missingWorkpieceKindRemainsADataGapInsteadOfBreakingTheQuery() {
		var result = new MesProductionTimeCalculator().calculate(query().setGroupBy("WORKPIECE"),
				List.of(fact("1", "A1", null, 0, 600).setIdentifier("SN1").setIdentifierType("SN")), START.plusHours(2));

		assertEquals(1, result.getTotals().getInvalidSourceCount());
		assertTrue(result.getGroups().isEmpty());
	}

	@Test
	void ongoingWorkpieceProcessingIsObservableWithoutAddingItToClosedTime() {
		var result = calculate(List.of(fact("1", "A1", "WORKPIECE_RUN", 0, 600)
				.setIdentifierType("SN").setIdentifier("SN1").setEndTime(null).setState("OPEN")));

		assertNull(result.getTotals().getWorkpieceProcessingSeconds());
		assertEquals("7200", result.getTotals().getOpenWorkpieceProcessingSeconds());
		assertEquals("7200", result.getGroups().get(0).getMetrics().getOpenWorkpieceProcessingSeconds());
		assertEquals(1, result.getTotals().getOpenIntervalCount());
	}

	@Test
	void observedSameMicrosecondSwitchIsZeroTimeRatherThanMissingEvidence() {
		var result = calculate(List.of(fact("1", "A1", "WORKPIECE_RUN", 0, 0)
				.setIdentifierType("SN").setIdentifier("SN1")));

		assertEquals("0", result.getTotals().getWorkpieceProcessingSeconds());
		assertEquals(0, result.getTotals().getInvalidSourceCount());
		assertEquals("0", result.getDetails().get(0).getSeconds());
	}

	private Result calculate(List<Fact> facts) {
		return new MesProductionTimeCalculator().calculate(query(), facts, START.plusHours(2));
	}

	private Query query() {
		return new Query().setStartTime(START).setEndTime(START.plusHours(2)).setGroupBy("TASK");
	}

	private Fact fact(String id, String arrange, String kind, long start, long end) {
		return new Fact().setSourceType("TEST_FACT").setSourceId(id).setTaskCode("T1").setProduceTaskCode("P1")
				.setArrangeCode(arrange).setKind(kind).setStartTime(START.plusSeconds(start)).setEndTime(START.plusSeconds(end))
				.setState("CLOSED").setEligible(true).setReason("MANUAL_PAUSE");
	}
}
