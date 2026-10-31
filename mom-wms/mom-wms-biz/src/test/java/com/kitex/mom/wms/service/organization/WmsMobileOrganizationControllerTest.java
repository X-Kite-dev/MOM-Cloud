package com.kitex.mom.wms.service.organization;

import com.kitex.mom.wms.controller.app.AppOrganizationTransferController;
import com.kitex.mom.wms.dto.WmsOrganizationTransferDTO.Command;
import com.kitex.mom.wms.dto.WmsOrganizationTransferDTO.PolicyCommand;
import com.kitex.mom.wms.dto.app.WmsMobileOrganizationTransferDTO.MobileContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 实际MVC路径和委派合同；角色、事务与库存语义由真实服务用例核对。
 */
class WmsMobileOrganizationControllerTest {

	private final WmsOrganizationTransferService service = mock(WmsOrganizationTransferService.class);
	private final WmsOrganizationTransferPolicyService policies = mock(WmsOrganizationTransferPolicyService.class);
	private MockMvc mvc;

	@BeforeEach
	void setup() {
		mvc = MockMvcBuilders.standaloneSetup(new AppOrganizationTransferController(service, policies,
				mock(WmsOrganizationTransferAccess.class), mock(WmsOrganizationTransferStockService.class))).build();
	}

	@Test
	void dedicatedContextPublishesTheMobileMarkerEvenWhenNewWorkIsDisabled() throws Exception {
		when(service.mobileContext("WA")).thenReturn(new MobileContext(1, 1L, "F1", "source", "WA",
				List.of(), false, 11L, 1, false, "该仓库未开启手机端作业"));

		mvc.perform(get("/app/organizationTransfer/context").param("warehouseCode", "WA"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.mobileContractVersion").value(1))
				.andExpect(jsonPath("$.data.mobileEnabled").value(false))
				.andExpect(jsonPath("$.data.factoryCode").value("F1"));
	}

	@Test
	void actionsCannotAccidentallyDelegateToThePcExecutionPath() throws Exception {
		mvc.perform(post("/app/organizationTransfer/actions").contentType(MediaType.APPLICATION_JSON)
						.content("{\"warehouseCode\":\"WA\",\"requestId\":\"ORG-original-00000001\",\"action\":\"CREATE\"}"))
				.andExpect(status().isOk());

		var command = ArgumentCaptor.forClass(Command.class);
		verify(service).executeMobile(command.capture());
		assertEquals("ORG-original-00000001", command.getValue().getRequestId());
		assertEquals("WA", command.getValue().getWarehouseCode());
		verify(service, never()).execute(any());
	}

	@Test
	void policyMaintenanceUsesItsMobileServiceEntry() throws Exception {
		mvc.perform(put("/app/organizationTransfer/policy").contentType(MediaType.APPLICATION_JSON)
						.content("{\"warehouseCode\":\"WA\",\"factoryCode\":\"F1\",\"expectedVersion\":1}"))
				.andExpect(status().isOk());

		var command = ArgumentCaptor.forClass(PolicyCommand.class);
		verify(policies).saveMobile(command.capture());
		assertEquals(1, command.getValue().getExpectedVersion());
		verify(policies, never()).save(any());
	}

	@Test
	void cancellationAndLookupKeepTheOriginalRecoveryEntries() throws Exception {
		mvc.perform(get("/app/organizationTransfer/operations/ORG-original-00000001")
				.param("warehouseCode", "WA")).andExpect(status().isOk());
		mvc.perform(post("/app/organizationTransfer/abandon").contentType(MediaType.APPLICATION_JSON)
						.content("{\"command\":{\"warehouseCode\":\"WA\",\"requestId\":\"ORG-original-00000001\"},\"reason\":\"核对后取消\"}"))
				.andExpect(status().isOk());

		verify(service).lookup("WA", "ORG-original-00000001");
		verify(service).abandon(any(Command.class), eq("核对后取消"));
		verify(service, never()).executeMobile(any());
	}
}
