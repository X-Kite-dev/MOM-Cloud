package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.HasPermission;
import com.kitex.mom.wms.service.WmsInventoryVariancePolicyService;
import com.kitex.mom.wms.service.impl.WmsInventoryVarianceGate;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsInsideInventoryPlan/variance-policy")
public class WmsInventoryVariancePolicyController {
	private final WmsInventoryVariancePolicyService policies;
	private final WmsInventoryVarianceGate gate;

	@GetMapping
	@Operation(summary = "读取仓库盘点差异规则")
	@PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_view_*','wms_wmsInsideInventoryPlan_view')")
	public R<WmsInventoryVariancePolicyService.Policy> get(@RequestParam String warehouseCode) {
		return R.ok(policies.effective(warehouseCode));
	}

	@PostMapping
	@HasPermission("wms_wmsWarehouseMenu_add")
	@SysLog("保存仓库盘点差异规则")
	@Operation(summary = "保存新盘点差异申请的复盘规则")
	public R<WmsInventoryVariancePolicyService.Policy> save(
			@RequestBody WmsInventoryVariancePolicyService.SaveCommand command) {
		return R.ok(policies.save(command));
	}

	@GetMapping("/check")
	@Operation(summary = "核对原盘点明细的复盘依据")
	@PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_view_*','wms_wmsInsideInventoryPlan_view')")
	public R<WmsInventoryVarianceGate.Check> check(@RequestParam Long planDetailId) {
		return R.ok(gate.inspect(planDetailId));
	}
}
