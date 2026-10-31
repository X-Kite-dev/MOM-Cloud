package com.kitex.mom.wms.service;

import com.kitex.mom.wms.controller.app.AppProductionReturnReceiptController;
import com.kitex.mom.wms.dto.app.WmsReturnFinalizeDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WmsMobileReturnFinalizeControllerTest {

	@Test
	void newMobileExecutionAndContextUseTheDedicatedServiceContract() throws Exception {
		var service = mock(WmsReturnFinalizeService.class);
		when(service.mobileContext(100L, "W1")).thenReturn(new WmsReturnFinalizeDTO.MobileContext(
				1, 1, 1L, "F1", "receiver", 100L, "W1", true, ""));
		var mvc = MockMvcBuilders.standaloneSetup(new AppProductionReturnReceiptController(service)).build();

		mvc.perform(get("/app/productionReturnReceipt/context").param("noticeId", "100")
						.param("warehouseCode", "W1")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.mobileContractVersion").value(1))
				.andExpect(jsonPath("$.data.noticeId").value(100));
		mvc.perform(post("/app/productionReturnReceipt/finalize/commands").contentType(MediaType.APPLICATION_JSON)
						.content("{\"requestId\":\"return-mobile-0001\",\"noticeId\":100,\"warehouseCode\":\"W1\","
								+ "\"overageQuantity\":0,\"reason\":\"现场收货完成\"}"))
				.andExpect(status().isOk());

		verify(service).mobileContext(100L, "W1");
		verify(service).finalizeMobile(any());
		verify(service, never()).finalizeReceipt(any());
		verify(service, never()).finalizeLegacy(any());
	}

	@Test
	void dedicatedRecoveryRoutesRetainOriginalLookupAndSafeCancellation() throws Exception {
		var service = mock(WmsReturnFinalizeService.class);
		var mvc = MockMvcBuilders.standaloneSetup(new AppProductionReturnReceiptController(service)).build();

		mvc.perform(get("/app/productionReturnReceipt/finalize/commands/return-mobile-0001")
				.param("noticeId", "100").param("warehouseCode", "W1")).andExpect(status().isOk());
		mvc.perform(post("/app/productionReturnReceipt/finalize/commands/cancel")
						.contentType(MediaType.APPLICATION_JSON).content("{\"command\":{"
								+ "\"requestId\":\"return-mobile-0001\",\"noticeId\":100,\"warehouseCode\":\"W1\","
								+ "\"overageQuantity\":0,\"reason\":\"现场收货完成\"},\"reason\":\"重新核对\"}"))
				.andExpect(status().isOk());

		verify(service).lookup("return-mobile-0001", 100L, "W1");
		verify(service).cancel(any(), eq("重新核对"));
		verify(service, never()).finalizeMobile(any());
		verify(service, never()).mobileContext(any(), any());
	}
}
