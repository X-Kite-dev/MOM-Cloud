package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.WmsInventoryRecountTerminationCommand;
import com.kitex.mom.wms.entity.WmsInventoryRecountRecordEntity;
import com.kitex.mom.wms.service.impl.WmsInventoryOwnerCountService;
import com.kitex.mom.wms.service.impl.WmsInventoryRecountRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsInsideInventoryPlan/owner-recount")
@PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_view_*','wms_wmsInsideInventoryPlan_view')")
public class WmsInventoryOwnerCountController {
	private final WmsInventoryOwnerCountService ownerCounts;
	private final WmsInventoryRecountRequestService requests;

	@GetMapping("/resolve")
	public R<WmsInventoryRecountRequestService.Resolution> resolve(@RequestParam Long planDetailId,
	                                                               @RequestParam String requestId) {
		return R.ok(requests.resolve(planDetailId, requestId));
	}

	@PostMapping("/terminate")
	@PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_hand_*','wms_wmsInsideInventoryPlan_hand','wms_wmsInsideInventoryPlan_edit')")
	public R<WmsInventoryRecountRequestService.Resolution> terminate(
			@RequestBody WmsInventoryRecountTerminationCommand command) {
		return R.ok(requests.terminate(command));
	}

	@GetMapping("/context")
	public R<WmsInventoryOwnerCountService.Context> context(@RequestParam Long planDetailId) {
		return R.ok(ownerCounts.context(planDetailId));
	}

	@GetMapping("/prepare")
	public R<WmsInventoryOwnerCountService.Prepare> prepare(@RequestParam Long planDetailId) {
		return R.ok(ownerCounts.prepare(planDetailId, false));
	}

	@GetMapping("/lookup")
	public R<WmsInventoryRecountRecordEntity> lookup(@RequestParam Long planDetailId, @RequestParam String requestId) {
		return R.ok(ownerCounts.lookup(planDetailId, requestId));
	}
}
