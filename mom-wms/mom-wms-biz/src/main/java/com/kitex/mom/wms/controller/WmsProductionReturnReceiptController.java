package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.WmsProductionReturnReceiptCommand;
import com.kitex.mom.wms.dto.app.WmsReturnFinalizeDTO;
import com.kitex.mom.wms.service.WmsReturnFinalizeService;
import com.kitex.mom.wms.service.impl.WmsProductionReturnReceiptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 生产退料实际收货终结与差异处置。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsProduceReturn/receipt")
public class WmsProductionReturnReceiptController {
	private final WmsProductionReturnReceiptService service;
	private final WmsReturnFinalizeService finalization;

	@GetMapping("/{noticeId}")
	public R<WmsProductionReturnReceiptCommand.View> detail(@PathVariable Long noticeId) {
		return R.ok(service.detail(noticeId));
	}

	@PostMapping("/finalize")
	public R<WmsProductionReturnReceiptCommand.View> finalizeReceipt(
			@RequestBody WmsProductionReturnReceiptCommand.Finalize command) {
		return R.ok(finalization.finalizeLegacy(command));
	}

	@PostMapping("/finalize/commands")
	public R<WmsReturnFinalizeDTO.View> finalizeCommand(@Valid @RequestBody WmsReturnFinalizeDTO.Command command) {
		return R.ok(finalization.finalizeReceipt(command));
	}

	@GetMapping("/finalize/commands/{requestId}")
	public R<WmsReturnFinalizeDTO.View> finalizeResult(@PathVariable String requestId,
			@RequestParam Long noticeId, @RequestParam String warehouseCode) {
		return R.ok(finalization.lookup(requestId, noticeId, warehouseCode));
	}

	@PostMapping("/finalize/commands/cancel")
	public R<WmsReturnFinalizeDTO.View> cancelFinalize(@Valid @RequestBody WmsReturnFinalizeDTO.Cancellation command) {
		return R.ok(finalization.cancel(command.command(), command.reason()));
	}

	@PostMapping("/dispose")
	public R<WmsProductionReturnReceiptCommand.View> dispose(
			@RequestBody WmsProductionReturnReceiptCommand.Disposition command) {
		return R.ok(service.dispose(command));
	}

	@PostMapping("/retry-delivery")
	public R<WmsProductionReturnReceiptCommand.View> retryDelivery(
			@RequestBody WmsProductionReturnReceiptCommand.RetryDelivery command) {
		return R.ok(service.retryDelivery(command));
	}
}
