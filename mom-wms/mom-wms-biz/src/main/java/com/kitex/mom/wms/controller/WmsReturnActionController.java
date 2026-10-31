package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.WmsReturnActionDTO;
import com.kitex.mom.wms.service.WmsReturnActionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 处置/续跑使用原请求恢复；仓库和原操作者权限由服务逐项核对。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsProduceReturn/receipt/actions")
public class WmsReturnActionController {
	private final WmsReturnActionService service;

	@PostMapping
	public R<WmsReturnActionDTO.View> execute(@Valid @RequestBody WmsReturnActionDTO.Command command) {
		return R.ok(service.execute(command));
	}

	@GetMapping("/{requestId}")
	public R<WmsReturnActionDTO.View> lookup(@PathVariable String requestId, @RequestParam Long noticeId,
			@RequestParam String warehouseCode, @RequestParam String action) {
		return R.ok(service.lookup(requestId, noticeId, warehouseCode, action));
	}

	@PostMapping("/cancel")
	public R<WmsReturnActionDTO.View> cancel(@Valid @RequestBody WmsReturnActionDTO.Cancellation command) {
		return R.ok(service.cancel(command));
	}
}
