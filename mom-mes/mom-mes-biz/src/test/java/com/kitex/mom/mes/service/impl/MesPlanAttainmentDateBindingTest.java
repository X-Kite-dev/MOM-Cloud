package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mes.controller.MesPlanAttainmentController;
import com.kitex.mom.mes.dto.MesPlanAttainmentDTO.Query;
import org.junit.jupiter.api.Test;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** 运行实际控制器注册的Spring绑定器，避免带偏移时间被静默当成本地时间。 */
class MesPlanAttainmentDateBindingTest {

	@Test
	void localSecondsAndDatabaseMicrosecondsBindWithoutChangingTheBusinessTime() throws Exception {
		for (String raw : new String[] { "2026-10-22T08:09:10", "2026-10-22T08:09:10.123456" }) {
			var query = new Query();
			var binder = bind(query, "startTime", raw);

			assertFalse(binder.getBindingResult().hasErrors(), raw);
			assertEquals(LocalDateTime.parse(raw), query.getStartTime());
		}
	}

	@Test
	void offsetsAreRejectedInsteadOfSilentlyDroppingTheTimezone() throws Exception {
		for (String raw : new String[] { "2026-10-22T08:09:10Z", "2026-10-22T08:09:10+08:00",
				"2026-10-22T08:09:10-04:00" }) {
			var query = new Query();
			var binder = bind(query, "startTime", raw);

			assertTrue(binder.getBindingResult().hasFieldErrors("startTime"), raw);
			assertNull(query.getStartTime(), raw);
		}
	}

	@Test
	void unsupportedPrecisionAndAmbiguousDateTextAreRejectedForBothBoundaries() throws Exception {
		for (String field : new String[] { "startTime", "endTime" }) {
			for (String raw : new String[] { "2026-10-22T08:09:10.123456789", "2026-10-22 08:09:10",
					"2026/10/22", "2026-02-30T08:09:10" }) {
				var query = new Query();
				var binder = bind(query, field, raw);

				assertTrue(binder.getBindingResult().hasFieldErrors(field), field + ": " + raw);
			}
		}
	}

	@Test
	void emptyBoundaryRemainsMissingForRequiredWindowValidation() throws Exception {
		var query = new Query();
		var binder = bind(query, "startTime", "");

		assertFalse(binder.getBindingResult().hasErrors());
		assertNull(query.getStartTime());
	}

	private WebDataBinder bind(Query query, String field, String raw) throws Exception {
		var controller = new MesPlanAttainmentController(mock(MesPlanAttainmentService.class));
		var binder = new WebDataBinder(query);
		binder.setConversionService(new DefaultFormattingConversionService());
		for (var method : MesPlanAttainmentController.class.getDeclaredMethods()) {
			if (method.isAnnotationPresent(InitBinder.class)) {
				method.setAccessible(true);
				method.invoke(controller, binder);
			}
		}
		binder.bind(new MutablePropertyValues(Map.of(field, raw)));
		return binder;
	}
}
