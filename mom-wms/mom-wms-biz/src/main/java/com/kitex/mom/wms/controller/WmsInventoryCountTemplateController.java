package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.HasPermission;
import com.kitex.mom.wms.service.WmsInventoryCountTemplateService;
import com.kitex.mom.wms.service.WmsInventoryCountScheduleConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 仓库盘点模板配置、只读预览与手动触发。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsInsideInventoryPlan/templates")
@Tag(name = "仓库盘点任务模板")
public class WmsInventoryCountTemplateController {
	private final WmsInventoryCountTemplateService templates;
	private final WmsInventoryCountScheduleConfigService schedules;

	@GetMapping
	@Operation(summary = "查询当前仓库盘点模板")
	@PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_add_*', 'wms_wmsInsideInventoryPlan_add')")
	public R<List<WmsInventoryCountTemplateService.Template>> list(@RequestParam String warehouseCode) {
		return R.ok(templates.list(warehouseCode));
	}

	@PostMapping
	@Operation(summary = "保存或停用盘点模板")
	@HasPermission("wms_wmsWarehouseMenu_add")
	@SysLog("保存仓库盘点模板")
	public R<WmsInventoryCountTemplateService.Template> save(
			@RequestBody WmsInventoryCountTemplateService.SaveCommand command) {
		return R.ok(templates.save(command));
	}

	@GetMapping("/schedule")
	@Operation(summary = "查询当前仓库盘点模板周期")
	@PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_add_*', 'wms_wmsInsideInventoryPlan_add')")
	public R<WmsInventoryCountScheduleConfigService.Schedule> schedule(
			@RequestParam String warehouseCode, @RequestParam String templateCode) {
		return R.ok(schedules.get(warehouseCode, templateCode));
	}

	@PostMapping("/schedule")
	@Operation(summary = "保存或暂停当前仓库盘点模板周期")
	@HasPermission("wms_wmsWarehouseMenu_add")
	@SysLog("配置仓库盘点周期")
	public R<WmsInventoryCountScheduleConfigService.Schedule> saveSchedule(
			@RequestBody WmsInventoryCountScheduleConfigService.SaveCommand command) {
		return R.ok(schedules.save(command));
	}

	@GetMapping("/preview")
	@Operation(summary = "按当前库存预览模板命中范围")
	@PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_add_*', 'wms_wmsInsideInventoryPlan_add')")
	public R<WmsInventoryCountTemplateService.Preview> preview(
			@RequestParam String warehouseCode, @RequestParam String templateCode,
			@RequestParam int expectedVersion) {
		return R.ok(templates.preview(warehouseCode, templateCode, expectedVersion));
	}

	@GetMapping("/runs")
	@Operation(summary = "查询模板最近触发的盘点计划")
	@PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_add_*', 'wms_wmsInsideInventoryPlan_add')")
	public R<List<WmsInventoryCountTemplateService.RunHistory>> recentRuns(
			@RequestParam String warehouseCode, @RequestParam String templateCode) {
		return R.ok(templates.recentRuns(warehouseCode, templateCode));
	}

	@PostMapping("/trigger")
	@Operation(summary = "按模板和当前库存生成可执行盘点计划")
	@PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_add_*', 'wms_wmsInsideInventoryPlan_add')")
	@SysLog("触发仓库盘点模板")
	public R<WmsInventoryCountTemplateService.RunResult> trigger(
			@RequestBody WmsInventoryCountTemplateService.RunCommand command) {
		return R.ok(templates.trigger(command));
	}
}
