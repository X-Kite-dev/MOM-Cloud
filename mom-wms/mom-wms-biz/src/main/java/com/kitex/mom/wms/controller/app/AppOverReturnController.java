package com.kitex.mom.wms.controller.app;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.WmsOverReturnDTO.*;
import com.kitex.mom.wms.service.overreturn.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * PDA原仓实收正式入口；身份和原仓岗位由服务层核验。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/transferVariance/overReturn")
public class AppOverReturnController {
	private final WmsOverReturnService service;

	/**
	 * 获取实际身份、原仓能力与下一批服务端号。
	 */
	@GetMapping("/context")
	public R<Context> context(@RequestParam String warehouseCode) {
		return R.ok(service.context(warehouseCode, true));
	}

	/**
	 * 授权与查询条件先于分页；历史含新返还已结案件。
	 */
	@GetMapping("/pending")
	public R<Page> pending(@RequestParam String warehouseCode, @RequestParam(defaultValue = "PENDING") String scope,
	                       @RequestParam(required = false) String billNo,
	                       @RequestParam(required = false) String barcode,
	                       @RequestParam(defaultValue = "1") long current,
	                       @RequestParam(defaultValue = "20") long size) {
		return R.ok(service.pending(warehouseCode, scope, billNo, barcode, current, size));
	}

	/**
	 * 回读原件、真实累计与永久履历。
	 */
	@GetMapping("/detail")
	public R<Detail> detail(@RequestParam String caseId) {
		return R.ok(service.detail(caseId));
	}

	/**
	 * 按原片段本次数量实际收货。
	 */
	@PostMapping("/receive")
	public R<Receipt> receive(@RequestBody Envelope command) {
		return R.ok(service.execute(command, "receive", true));
	}

	/**
	 * 核对本人原号永久结果。
	 */
	@PostMapping("/lookup")
	public R<Receipt> lookup(@RequestBody Envelope command) {
		return R.ok(service.execute(command, "lookup", true));
	}

	/**
	 * 永久结束本人尚未受理原号，不取消实物运输。
	 */
	@PostMapping("/end")
	public R<Receipt> end(@RequestBody Envelope command) {
		return R.ok(service.execute(command, "end", true));
	}
}
