package com.kitex.mom.wms.controller.app;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.app.WmsMobileTransferReceiptDTO.Cancellation;
import com.kitex.mom.wms.dto.app.WmsMobileTransferReceiptDTO.Command;
import com.kitex.mom.wms.dto.app.WmsMobileTransferReceiptDTO.Context;
import com.kitex.mom.wms.dto.app.WmsMobileTransferReceiptDTO.PendingPage;
import com.kitex.mom.wms.dto.app.WmsMobileTransferReceiptDTO.Source;
import com.kitex.mom.wms.dto.app.WmsMobileTransferReceiptDTO.View;
import com.kitex.mom.wms.service.WmsMobileTransferReceiptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 目标仓标准在途接收；每个入口均在服务端校验实际身份和仓库操作权限。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/transfer/receipts")
public class AppTransferReceiptController {
	private final WmsMobileTransferReceiptService service;

	@GetMapping("/context")
	public R<Context> context() {
		return R.ok(service.context());
	}

	@GetMapping("/sources")
	public R<Source> sources(@RequestParam String billNo, @RequestParam String warehouseCode) {
		return R.ok(service.sources(billNo, warehouseCode));
	}

	/**
	 * @param warehouseCode 当前实际目标仓库，不能无仓查询
	 * @param reference     单号、来源仓库或备注的查询文本，可为空
	 * @param page          1至100，每页最多20条
	 * @return 当前已授权并启用移动调拨接收的真实在途候选；选择后仍重读正式来源
	 */
	@GetMapping("/pending")
	public R<PendingPage> pending(@RequestParam String warehouseCode,
	                              @RequestParam(defaultValue = "") String reference,
	                              @RequestParam(defaultValue = "1") int page) {
		return R.ok(service.pending(warehouseCode, reference, page));
	}

	@PostMapping
	public R<View> receive(@Valid @RequestBody Command command) {
		return R.ok(service.receive(command));
	}

	@GetMapping("/{requestId}")
	public R<View> lookup(@PathVariable String requestId, @RequestParam Long allocationBillId,
	                      @RequestParam String warehouseCode) {
		return R.ok(service.lookup(requestId, allocationBillId, warehouseCode));
	}

	@PostMapping("/cancel")
	public R<View> cancel(@Valid @RequestBody Cancellation input) {
		return R.ok(service.cancel(input.command(), input.reason()));
	}
}
