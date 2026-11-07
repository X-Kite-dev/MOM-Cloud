package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.service.WmsInventoryCountEventConfigService;
import com.kitex.mom.wms.service.WmsInventoryCountEventDispatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsInsideInventoryPlan/templates/events")
@PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_add_*', 'wms_wmsInsideInventoryPlan_add')")
public class WmsInventoryCountEventController {
	private final WmsInventoryCountEventConfigService configs;
	private final WmsInventoryCountEventDispatchService dispatcher;

	@GetMapping
	public R<WmsInventoryCountEventConfigService.Policy> get(@RequestParam String warehouseCode,
	                                                         @RequestParam String templateCode) {
		return R.ok(configs.get(warehouseCode, templateCode));
	}

	@PostMapping
	@SysLog("保存预警转盘点规则")
	public R<WmsInventoryCountEventConfigService.Policy> save(
			@RequestBody WmsInventoryCountEventConfigService.Command c) {
		return R.ok(configs.save(c));
	}

	@GetMapping("/history")
	public R<List<WmsInventoryCountEventConfigService.History>> history(@RequestParam String warehouseCode,
	                                                                    @RequestParam String templateCode) {
		return R.ok(configs.history(warehouseCode, templateCode));
	}

	@PostMapping("/process")
	@SysLog("处理模板待触发预警")
	public R<WmsInventoryCountEventDispatchService.Summary> process(@RequestBody Target t) {
		return R.ok(dispatcher.process(t.warehouseCode(), t.templateCode()));
	}

	@PostMapping("/retry")
	@SysLog("按原预警重试盘点触发")
	public R<String> retry(@RequestBody Retry t) {
		return R.ok(dispatcher.retry(t.warehouseCode(), t.templateCode(), t.eventId()), "处理已完成");
	}

	public record Target(String warehouseCode, String templateCode) {
	}

	public record Retry(String warehouseCode, String templateCode, String eventId) {
	}
}
