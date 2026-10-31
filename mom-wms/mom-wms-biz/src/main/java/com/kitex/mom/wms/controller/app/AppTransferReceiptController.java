package com.kitex.mom.wms.controller.app;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.app.WmsMobileTransferReceiptDTO.Cancellation;
import com.kitex.mom.wms.dto.app.WmsMobileTransferReceiptDTO.Command;
import com.kitex.mom.wms.dto.app.WmsMobileTransferReceiptDTO.Context;
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

/** 目标仓标准在途接收；每个入口均在服务端校验实际身份和仓库操作权限。 */
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

	@PostMapping
	public R<View> receive(@Valid @RequestBody Command command) {
		return R.ok(service.receive(command));
	}

	@GetMapping("/{requestId}")
	public R<View> lookup(@PathVariable String requestId, @RequestParam Long allocationBillId, @RequestParam String warehouseCode) {
		return R.ok(service.lookup(requestId, allocationBillId, warehouseCode));
	}

	@PostMapping("/cancel")
	public R<View> cancel(@Valid @RequestBody Cancellation input) {
		return R.ok(service.cancel(input.command(), input.reason()));
	}
}
