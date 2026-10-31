package com.kitex.mom.wms.service.impl;

import com.kitex.mom.wms.controller.app.AppInventoryPlanController;
import com.kitex.mom.wms.entity.WmsInventoryAdjustmentApprovalEntity;
import com.kitex.mom.wms.service.WmsInsideInventoryPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 专用MVC写口必须委派mobile方法；真实范围/状态与事务另由服务用例覆盖。
 */
class WmsMobileInventoryControllerTest {

	private final WmsInsideInventoryPlanService plans = mock(WmsInsideInventoryPlanService.class);
	private final WmsInventoryAdjustmentService adjustments = mock(WmsInventoryAdjustmentService.class);
	private final WmsInventoryAdjustmentApprovalService approvals = mock(WmsInventoryAdjustmentApprovalService.class);
	private final WmsInventoryMobileAccess mobile = mock(WmsInventoryMobileAccess.class);
	private final WmsInventoryWarehousePermission permissions = mock(WmsInventoryWarehousePermission.class);
	private MockMvc mvc;

	@BeforeEach
	void setup() {
		mvc = MockMvcBuilders.standaloneSetup(new AppInventoryPlanController(plans, adjustments, approvals,
				mobile, permissions)).build();
	}

	@Test
	void creationAndCompletionCannotUseThePcMutators() throws Exception {
		mvc.perform(post("/app/inventoryPlan").contentType(MediaType.APPLICATION_JSON)
						.content("{\"warehouseCode\":\"W1\",\"planNo\":\"PD-1\"}"))
				.andExpect(status().isOk());
		mvc.perform(put("/app/inventoryPlan/complete/1")).andExpect(status().isOk());

		verify(permissions).require("add", "W1");
		verify(mobile).plan(1L, "hand");
		verify(plans).saveDeepMobile(any());
		verify(plans).completeMobile(1L);
		verify(plans, never()).saveDeep(any());
		verify(plans, never()).complete(any());
	}

	@Test
	void adjustmentAndApprovalsAlwaysUseTheMobileServiceMethodsAndOriginalDetail() throws Exception {
		var approval = new WmsInventoryAdjustmentApprovalEntity();
		approval.setPlanDetailId(2L);
		when(approvals.get(3L)).thenReturn(approval);
		mvc.perform(post("/app/inventoryPlan/adjust").contentType(MediaType.APPLICATION_JSON)
				.content("{\"planDetailId\":2}")).andExpect(status().isOk());
		mvc.perform(post("/app/inventoryPlan/adjustment-approvals").contentType(MediaType.APPLICATION_JSON)
				.content("{\"planDetailId\":2}")).andExpect(status().isOk());
		mvc.perform(post("/app/inventoryPlan/adjustment-approvals/review").contentType(MediaType.APPLICATION_JSON)
				.content("{\"approvalId\":3}")).andExpect(status().isOk());

		verify(mobile, times(3)).detail(2L, "hand");
		verify(adjustments).adjustMobile(any());
		verify(approvals).submitMobile(any());
		verify(approvals).reviewMobile(any());
		verify(adjustments, never()).adjust(any());
		verify(approvals, never()).submit(any());
		verify(approvals, never()).review(any());
	}
}
