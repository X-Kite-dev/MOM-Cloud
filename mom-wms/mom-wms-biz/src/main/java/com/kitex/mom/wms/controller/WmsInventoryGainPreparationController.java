package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsInventoryGainPreparationCommand;
import com.kitex.mom.wms.service.impl.WmsInventoryGainPreparationService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class WmsInventoryGainPreparationController {
	private static final String HAND = "@pms.hasPermission('wms_wmsInsideInventoryPlan_hand_*', "
			+ "'wms_wmsInsideInventoryPlan_hand', 'wms_wmsInsideInventoryPlan_edit')";
	private final WmsInventoryGainPreparationService service;

	@PostMapping("/wmsInsideInventoryPlan/gain-attributes")
	@PreAuthorize(HAND)
	@Operation(summary = "按原盘点范围准备盘盈属性")
	@SysLog("准备盘盈属性")
	public R<WmsInventoryGainPreparationService.Prepared> prepare(
			@RequestBody WmsInventoryGainPreparationCommand command) {
		return R.ok(service.prepare(command, false));
	}

	@PostMapping("/app/inventoryPlan/gain-attributes")
	@PreAuthorize(HAND)
	@Operation(summary = "手机端按原盘点范围准备盘盈属性")
	@SysLog("手机端准备盘盈属性")
	public R<WmsInventoryGainPreparationService.Prepared> prepareMobile(
			@RequestBody WmsInventoryGainPreparationCommand command) {
		return R.ok(service.prepare(command, true));
	}
}
