package com.kitex.mom.wms.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.service.WmsInventoryCountScheduleDispatchService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsInventoryCountScheduleJobTest {
	private final WmsInventoryCountScheduleDispatchService dispatcher =
			mock(WmsInventoryCountScheduleDispatchService.class);
	private final WmsInventoryCountScheduleJob job =
			new WmsInventoryCountScheduleJob(dispatcher, new ObjectMapper());

	@Test
	void explicitScopeCanBeInvokedManuallyWithoutAnXxlServer() throws Exception {
		when(dispatcher.runScope(11L, "F1", 5))
				.thenReturn(new WmsInventoryCountScheduleDispatchService.Summary(2, 1, 0));

		var result = job.dispatchParameter("{\"tenantId\":11,\"factoryCode\":\"F1\",\"limit\":5}");

		assertEquals(1, result.created());
		verify(dispatcher).runScope(11L, "F1", 5);
	}

	@Test
	void missingOrInvalidScopeDoesNotRunTheDispatcher() {
		assertThrows(CheckedException.class, () -> job.dispatchParameter("{}"));
		assertThrows(CheckedException.class, () -> job.dispatchParameter(
				"{\"tenantId\":11,\"factoryCode\":\"F1\",\"limit\":0}"));
		assertThrows(CheckedException.class, () -> job.dispatchParameter(
				"{\"tenantId\":\"11\",\"factoryCode\":\"F1\"}"));
		verify(dispatcher, never()).runScope(11L, "F1", 5);
	}
}
