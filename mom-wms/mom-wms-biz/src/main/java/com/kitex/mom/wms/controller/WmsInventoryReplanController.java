package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.WmsInventoryReplanCommand;
import com.kitex.mom.wms.dto.WmsInventoryReplanEndCommand;
import com.kitex.mom.wms.service.impl.WmsInventoryReplanService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsInsideInventoryPlan/replan")
@PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_view_*','wms_wmsInsideInventoryPlan_view')")
public class WmsInventoryReplanController {
	private final WmsInventoryReplanService service;

	@GetMapping("/context")
	public R<WmsInventoryReplanService.Context> context(@RequestParam Long sourcePlanId) {
		return R.ok(service.context(sourcePlanId));
	}

	@GetMapping("/prepare")
	public R<WmsInventoryReplanService.Prepare> prepare(@RequestParam Long sourcePlanId) {
		return R.ok(service.prepare(sourcePlanId, false));
	}

	@GetMapping("/resolve")
	public R<WmsInventoryReplanService.Resolution> resolve(@RequestParam Long sourcePlanId,
	                                                       @RequestParam String requestId) {
		return R.ok(service.resolve(sourcePlanId, requestId));
	}

	@PostMapping
	@PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_add_*','wms_wmsInsideInventoryPlan_add')")
	public R<WmsInventoryReplanService.Resolution> create(@RequestBody WmsInventoryReplanCommand command) {
		return R.ok(service.create(command, false));
	}

	@PostMapping("/end")
	@PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_add_*','wms_wmsInsideInventoryPlan_add')")
	public R<WmsInventoryReplanService.Resolution> end(@RequestBody WmsInventoryReplanEndCommand command) {
		return R.ok(service.end(command));
	}
}
