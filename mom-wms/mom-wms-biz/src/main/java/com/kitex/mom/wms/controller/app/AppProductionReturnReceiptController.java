package com.kitex.mom.wms.controller.app;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.app.WmsReturnFinalizeDTO;
import com.kitex.mom.wms.service.WmsReturnFinalizeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 手机端生产退料终结；原通知范围与逐仓办理权限由同一业务服务核验。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/productionReturnReceipt")
public class AppProductionReturnReceiptController {

	private final WmsReturnFinalizeService finalization;

	@GetMapping("/context")
	public R<WmsReturnFinalizeDTO.MobileContext> context(@RequestParam Long noticeId,
	                                                     @RequestParam String warehouseCode) {
		return R.ok(finalization.mobileContext(noticeId, warehouseCode));
	}

	@PostMapping("/finalize/commands")
	@SysLog("手机端结束生产退料收货")
	public R<WmsReturnFinalizeDTO.View> finalizeCommand(@Valid @RequestBody WmsReturnFinalizeDTO.Command command) {
		return R.ok(finalization.finalizeMobile(command));
	}

	@GetMapping("/finalize/commands/{requestId}")
	public R<WmsReturnFinalizeDTO.View> result(@PathVariable String requestId,
	                                           @RequestParam Long noticeId, @RequestParam String warehouseCode) {
		return R.ok(finalization.lookup(requestId, noticeId, warehouseCode));
	}

	@PostMapping("/finalize/commands/cancel")
	@SysLog("手机端核销生产退料终结原请求")
	public R<WmsReturnFinalizeDTO.View> cancel(@Valid @RequestBody WmsReturnFinalizeDTO.Cancellation command) {
		return R.ok(finalization.cancel(command.command(), command.reason()));
	}
}
