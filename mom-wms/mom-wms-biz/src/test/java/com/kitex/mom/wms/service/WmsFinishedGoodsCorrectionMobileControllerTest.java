package com.kitex.mom.wms.service;

import com.kitex.mom.wms.controller.app.AppFinishedGoodsReceiptCorrectionController;
import com.kitex.mom.wms.dto.app.WmsMobileFinishedGoodsCorrectionDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WmsFinishedGoodsCorrectionMobileControllerTest {
	@Test
	void contextAndNewApplicationUseTheDedicatedMobileContract() throws Exception {
		var service = mock(WmsFinishedGoodsReceiptCorrectionService.class);
		var access = mock(WmsFinishedGoodsCorrectionMobileAccess.class);
		var submissions = mock(WmsFinishedGoodsCorrectionSubmissionService.class);
		when(access.context(any())).thenReturn(new WmsMobileFinishedGoodsCorrectionDTO.Context(1, 1,
				"1", "F1", "operator", "100", "W1", List.of("400"), true, ""));
		var mvc = MockMvcBuilders.standaloneSetup(new AppFinishedGoodsReceiptCorrectionController(service, access, submissions)).build();

		mvc.perform(post("/app/finishedGoodsReceiptCorrection/context").contentType(MediaType.APPLICATION_JSON)
						.content("{\"noticeId\":100,\"warehouseCode\":\"W1\",\"stockIds\":[400]}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.stockIds[0]").value("400"));
		mvc.perform(post("/app/finishedGoodsReceiptCorrection/apply").contentType(MediaType.APPLICATION_JSON)
						.content("{\"noticeId\":100,\"requestId\":\"FG-MOBILE-ORIGINAL-001\",\"reason\":\"批次核对\"}"))
				.andExpect(status().isOk());

		verify(service).applyMobile(any());
		verify(service, never()).apply(any());
	}

	@Test
	void originalPostLookupDoesNotInvokeNewMobilePreflightOrApplication() throws Exception {
		var service = mock(WmsFinishedGoodsReceiptCorrectionService.class);
		var access = mock(WmsFinishedGoodsCorrectionMobileAccess.class);
		var submissions = mock(WmsFinishedGoodsCorrectionSubmissionService.class);
		var mvc = MockMvcBuilders.standaloneSetup(new AppFinishedGoodsReceiptCorrectionController(service, access, submissions)).build();

		mvc.perform(post("/app/finishedGoodsReceiptCorrection/submissions/lookup").contentType(MediaType.APPLICATION_JSON)
						.content("{\"warehouseCode\":\"W1\",\"command\":{\"noticeId\":100,\"requestId\":\"FG-MOBILE-ORIGINAL-001\"}}"))
				.andExpect(status().isOk());

		verify(submissions).lookup(argThat(query -> "W1".equals(query.warehouseCode()) && query.command().getNoticeId() == 100L));
		verifyZeroInteractions(access, service);
	}
}
