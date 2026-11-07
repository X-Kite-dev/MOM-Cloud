package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.service.impl.WmsInventoryLossCandidateService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class WmsInventoryLossCandidateController {
	private static final String HAND = "@pms.hasPermission('wms_wmsInsideInventoryPlan_hand_*', "
			+ "'wms_wmsInsideInventoryPlan_hand', 'wms_wmsInsideInventoryPlan_edit')";
	private final WmsInventoryLossCandidateService service;

	@GetMapping("/wmsInsideInventoryPlan/loss-stocks")
	@PreAuthorize(HAND)
	@Operation(summary = "查询原盘亏范围的可选实物")
	public R<WmsInventoryLossCandidateService.Result> candidates(@RequestParam Long planDetailId,
	                                                             @RequestParam(required = false) String keyword,
	                                                             @RequestParam(required = false) String cursor) {
		return R.ok(service.candidates(planDetailId, keyword, cursor, false));
	}

	@GetMapping("/app/inventoryPlan/loss-stocks")
	@PreAuthorize(HAND)
	@Operation(summary = "手机端查询原盘亏范围的可选实物")
	public R<WmsInventoryLossCandidateService.Result> candidatesMobile(@RequestParam Long planDetailId,
	                                                                   @RequestParam(required = false) String keyword,
	                                                                   @RequestParam(required = false) String cursor) {
		return R.ok(service.candidates(planDetailId, keyword, cursor, true));
	}
}
