package com.kitex.mom.wms.controller.app;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.WmsFinishedGoodsCorrectionSubmissionDTO.Query;
import com.kitex.mom.wms.dto.WmsFinishedGoodsCorrectionSubmissionDTO.Receipt;
import com.kitex.mom.wms.service.WmsFinishedGoodsCorrectionSubmissionService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST仅携带原命令作只读核对，不将质量授权码放入URL。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/finishedGoodsReceiptCorrection/submissions")
public class AppFinishedGoodsCorrectionSubmissionController {

	private final WmsFinishedGoodsCorrectionSubmissionService service;

	@PostMapping("/lookup")
	@Operation(summary = "按原申请核对成品入库纠错登记结果")
	public R<Receipt> lookup(@RequestBody Query query) {
		return R.ok(service.lookup(query));
	}
}
