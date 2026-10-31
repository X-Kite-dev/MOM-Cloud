package com.kitex.mom.wms.controller.app;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.app.WmsMobileReceiptHistoryDTO.Page;
import com.kitex.mom.wms.dto.app.WmsMobileOutboundReceiptDTO.Cancellation;
import com.kitex.mom.wms.dto.app.WmsMobileOutboundReceiptDTO.Command;
import com.kitex.mom.wms.dto.app.WmsMobileOutboundReceiptDTO.Context;
import com.kitex.mom.wms.dto.app.WmsMobileOutboundReceiptDTO.View;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.service.WmsMobileOutboundReceiptService;
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
 * 新移动出库合同；仓库权限及永久原请求身份由服务统一校验。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/outBill/receipts")
public class AppOutboundReceiptController {

	private final WmsMobileOutboundReceiptService service;

	@GetMapping("/context")
	@Operation(summary = "读取出库实际组织与操作人")
	public R<Context> context() {
		return R.ok(service.context());
	}

	@GetMapping("/sources")
	@Operation(summary = "按仓库操作权限读取通知及推荐库存")
	public R<WmsOutBillEntity> sources(@RequestParam Long noticeId, @RequestParam String billType,
	                                   @RequestParam(required = false) String warehouseCode) {
		return R.ok(service.sources(noticeId, warehouseCode, billType));
	}

	@GetMapping("/history")
	@Operation(summary = "查询本人近三十天出库回执")
	public R<Page<View>> history(@RequestParam String warehouseCode, @RequestParam String billType,
	                             @RequestParam(defaultValue = "") String reference,
	                             @RequestParam(defaultValue = "") String status,
	                             @RequestParam(defaultValue = "1") int page) {
		return R.ok(service.history(warehouseCode, billType, reference, status, page));
	}

	@PostMapping
	@Operation(summary = "按原请求登记移动出库")
	public R<View> issue(@Valid @RequestBody Command command) {
		return R.ok(service.issue(command));
	}

	@GetMapping("/{requestId}")
	@Operation(summary = "按原请求核对出库回执")
	public R<View> lookup(@PathVariable String requestId, @RequestParam Long noticeId,
	                      @RequestParam String warehouseCode) {
		return R.ok(service.lookup(requestId, noticeId, warehouseCode));
	}

	@PostMapping("/cancel")
	@Operation(summary = "封存未执行原请求或返回原出库结果")
	public R<View> cancel(@Valid @RequestBody Cancellation cancellation) {
		return R.ok(service.cancel(cancellation.command(), cancellation.reason()));
	}
}
