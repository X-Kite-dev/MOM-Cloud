package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.WmsOverReturnDTO.*;
import com.kitex.mom.wms.service.overreturn.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Web溢收实际返还正式入口；身份和原仓岗位由服务层核验。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsTransferVariance/overReturn")
public class WmsOverReturnController {
	private final WmsOverReturnService service;
	private final WmsOverReturnDispatchService dispatch;
	private final WmsOverReturnDispatchProtocol protocol;

	/**
	 * 获取实际身份、原仓能力与下一批服务端号。
	 */
	@GetMapping("/context")
	public R<Context> context(@RequestParam String warehouseCode) {
		return R.ok(service.context(warehouseCode, false));
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
		return R.ok(service.execute(command, "receive", false));
	}

	/**
	 * 核对本人原号永久结果。
	 */
	@PostMapping("/lookup")
	public R<Receipt> lookup(@RequestBody Envelope command) {
		return R.ok(service.execute(command, "lookup", false));
	}

	/**
	 * 永久结束本人尚未受理原号，不取消实物运输。
	 */
	@PostMapping("/end")
	public R<Receipt> end(@RequestBody Envelope command) {
		return R.ok(service.execute(command, "end", false));
	}

	/**
	 * 获取目标仓派出身份和服务端号码。
	 */
	@GetMapping("/dispatch/context")
	public R<DispatchContext> dispatchContext(@RequestParam String caseId) {
		return R.ok(protocol.context(caseId));
	}

	/**
	 * 派出原号dispatch，全文冻结裁决。
	 */
	@PostMapping("/dispatch")
	public R<DispatchResult> dispatchDispatch(@RequestBody DispatchCommand command) {
		return R.ok(dispatch.execute(command, "dispatch"));
	}

	/**
	 * 派出原号lookup，全文冻结裁决。
	 */
	@PostMapping("/dispatch/lookup")
	public R<DispatchResult> dispatchLookup(@RequestBody DispatchCommand command) {
		return R.ok(dispatch.execute(command, "lookup"));
	}

	/**
	 * 派出原号end，全文冻结裁决。
	 */
	@PostMapping("/dispatch/end")
	public R<DispatchResult> dispatchEnd(@RequestBody DispatchCommand command) {
		return R.ok(dispatch.execute(command, "end"));
	}
}
