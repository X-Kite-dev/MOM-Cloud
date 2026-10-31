package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesProductionTimeDTO.*;
import com.kitex.mom.mes.mapper.MesProductionTimeMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MesProductionTimeServiceTest {

	private final MesActualFactoryAccessService access = mock(MesActualFactoryAccessService.class);
	private final MesProductionTimeMapper mapper = mock(MesProductionTimeMapper.class);
	private final MesProductionTimeService service = new MesProductionTimeService(access, mapper);

	@BeforeEach
	void setup() {
		TenantContextHolder.setTenantId(1L);
		when(access.require("read")).thenReturn("F1");
		when(mapper.segments(anyLong(), anyString(), any(), anyInt())).thenReturn(List.of());
		when(mapper.labor(anyLong(), anyString(), any(), anyInt())).thenReturn(List.of());
		when(mapper.pauses(anyLong(), anyString(), any(), anyInt())).thenReturn(List.of());
		when(mapper.workpieces(anyLong(), anyString(), any(), anyInt())).thenReturn(List.of());
	}

	@AfterEach
	void cleanup() {
		TenantContextHolder.clear();
	}

	@Test
	void missingOrReversedWindowIsRejectedBeforeSourceReads() {
		assertThrows(CheckedException.class, () -> service.statistics(new Query()));
		assertThrows(CheckedException.class, () -> service.statistics(query().setEndTime(query().getStartTime())));
		verifyZeroInteractions(mapper);
	}

	@Test
	void normalizesFiltersWithoutMutatingCallerAndUsesActualScope() {
		var query = query().setTaskCode(" T1 ");
		var result = service.statistics(query);

		assertEquals("F1", result.getFactoryCode());
		assertEquals("T1", result.getScope().getTaskCode());
		assertEquals(" T1 ", query.getTaskCode());
		verify(mapper).segments(eq(1L), eq("F1"), argThat(q -> "T1".equals(q.getTaskCode())), eq(20001));
	}

	@Test
	void sourceLimitIsAnErrorInsteadOfPartialHealthyStatistics() {
		when(mapper.segments(anyLong(), anyString(), any(), anyInt())).thenReturn(Collections.nCopies(20001, new Fact()));

		assertThrows(CheckedException.class, () -> service.statistics(query()));
	}

	@Test
	void oversizedWholeFactoryWindowAndInvalidPagingAreRejected() {
		assertThrows(CheckedException.class, () -> service.statistics(query().setEndTime(query().getStartTime().plusDays(32))));
		assertThrows(CheckedException.class, () -> service.statistics(query().setDetailSize(201)));
		assertThrows(CheckedException.class, () -> service.statistics(query().setGroupBy("FREE_SQL")));
	}

	@Test
	void returnsRealProcessingAndKeepsUnrecordedWorkpieceTimeUnknown() {
		when(mapper.segments(anyLong(), anyString(), any(), anyInt())).thenReturn(List.of(new Fact()
				.setSourceType("RUN_SEGMENT").setSourceId("1").setTaskCode("T1").setArrangeCode("A1")
				.setKind("RUN").setStartTime(query().getStartTime()).setEndTime(query().getStartTime().plusHours(1))
				.setState("CLOSED").setEligible(true)));
		var result = service.statistics(query());

		assertEquals("3600", result.getTotals().getProcessingSeconds());
		assertNull(result.getTotals().getWorkpieceProcessingSeconds());
		assertFalse(result.getDataGaps().isEmpty());
	}

	@Test
	void unavailableSourceCannotBecomeAnEmptySuccessfulResponse() {
		when(mapper.pauses(anyLong(), anyString(), any(), anyInt())).thenReturn(null);

		assertThrows(CheckedException.class, () -> service.statistics(query()));
	}

	@Test
	void missingArrangementAttributionIsReportedEvenWhenSourceJoinsExcludeIt() {
		when(mapper.orphanedSources(eq(1L), eq("F1"), any())).thenReturn(2L);
		var result = service.statistics(query());

		assertTrue(result.getDataGaps().stream().anyMatch(gap -> gap.contains("2") && gap.contains("派工")));
	}

	@Test
	void exactIdentifierQueryReadsOnlyWorkpieceFactsAndEchoesOriginalIdentity() {
		Query source = identifierQuery(query().setTaskCode(" T1 "), " SN ", " SN-1 ");
		var result = service.statistics(source);
		assertEquals("T1", result.getScope().getTaskCode());
		assertEquals(" SN-1 ", result.getScope().getIdentifier());
		assertEquals(" SN ", result.getScope().getIdentifierType());
		verify(mapper).workpieces(eq(1L), eq("F1"), any(), eq(20001));
		verify(mapper, never()).segments(anyLong(), anyString(), any(), anyInt());
		verify(mapper, never()).labor(anyLong(), anyString(), any(), anyInt());
		verify(mapper, never()).pauses(anyLong(), anyString(), any(), anyInt());
	}

	@Test
	void partialOrUnboundedIdentifierQueryIsRejectedBeforeReads() {
		for (Query source : List.of(identifierQuery(query(), "SN", "SN1"),
				identifierQuery(query().setTaskCode("T1"), null, "SN1"),
				identifierQuery(query().setTaskCode("T1"), "SN", null),
				identifierQuery(query().setTaskCode("T1"), "SN", " "),
				identifierQuery(query().setTaskCode("T1"), "SN", "SN\n1"),
				identifierQuery(query().setTaskCode("T1"), "SN", "x".repeat(201)))) {
			assertThrows(CheckedException.class, () -> service.statistics(source));
		}
		verifyZeroInteractions(mapper);
	}

	private Query identifierQuery(Query base, String type, String identifier) {
		var fields = new java.util.HashMap<String, Object>();
		fields.put("identifierType", type);
		fields.put("identifier", identifier);
		return assertDoesNotThrow(() -> new com.fasterxml.jackson.databind.ObjectMapper().updateValue(base, fields),
				"query must support exact production identifiers before filtering/pagination");
	}

	private Query query() {
		return new Query().setStartTime(LocalDateTime.parse("2026-10-22T08:00:00"))
				.setEndTime(LocalDateTime.parse("2026-10-22T18:00:00"));
	}
}
