package com.kitex.mom.wms.controller.app;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.app.WmsMobileReceiptHistoryDTO.Page;
import com.kitex.mom.wms.dto.app.WmsMobileInboundReceiptDTO.Cancellation;
import com.kitex.mom.wms.dto.app.WmsMobileInboundReceiptDTO.Command;
import com.kitex.mom.wms.dto.app.WmsMobileInboundReceiptDTO.Context;
import com.kitex.mom.wms.dto.app.WmsMobileInboundReceiptDTO.View;
import com.kitex.mom.wms.service.WmsMobileInboundReceiptService;
import io.swagger.v3.oas.annotations.Operation;
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
 * 新移动收货合同；仓库权限及永久原请求身份由服务统一校验。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/inBill/receipts")
public class AppInboundReceiptController {

	private final WmsMobileInboundReceiptService service;

	@GetMapping("/context")
	@Operation(summary = "读取收货实际组织与操作人")
	public R<Context> context() {
		return R.ok(service.context());
	}

	@GetMapping("/history")
	@Operation(summary = "查询本人近三十天收货回执")
	public R<Page<View>> history(@RequestParam String warehouseCode, @RequestParam String billType,
	                             @RequestParam(defaultValue = "") String reference,
	                             @RequestParam(defaultValue = "") String status,
	                             @RequestParam(defaultValue = "1") int page) {
		return R.ok(service.history(warehouseCode, billType, reference, status, page));
	}

	@PostMapping
	@Operation(summary = "按原请求登记移动收货")
	public R<View> receive(@Valid @RequestBody Command command) {
		return R.ok(service.receive(command));
	}

	@GetMapping("/{requestId}")
	@Operation(summary = "按原请求核对收货回执")
	public R<View> lookup(@PathVariable String requestId, @RequestParam Long noticeId,
	                      @RequestParam String warehouseCode) {
		return R.ok(service.lookup(requestId, noticeId, warehouseCode));
	}

	@PostMapping("/cancel")
	@Operation(summary = "封存未执行原请求或返回原收货结果")
	public R<View> cancel(@Valid @RequestBody Cancellation cancellation) {
		return R.ok(service.cancel(cancellation.command(), cancellation.reason()));
	}
}
