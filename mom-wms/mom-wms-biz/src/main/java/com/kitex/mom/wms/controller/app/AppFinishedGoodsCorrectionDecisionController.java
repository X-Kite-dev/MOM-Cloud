package com.kitex.mom.wms.controller.app;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.WmsFinishedGoodsCorrectionDecisionDTO.Query;
import com.kitex.mom.wms.dto.WmsFinishedGoodsCorrectionDecisionDTO.Receipt;
import com.kitex.mom.wms.service.WmsFinishedGoodsCorrectionDecisionService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 原办理正文仅用于只读核对，服务按原动作及冻结仓库校验实际权限。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/finishedGoodsReceiptCorrection/decisions")
public class AppFinishedGoodsCorrectionDecisionController {

	private final WmsFinishedGoodsCorrectionDecisionService service;

	@PostMapping("/lookup")
	@Operation(summary = "按原操作核对成品纠错复核或撤回结果")
	public R<Receipt> lookup(@RequestBody Query query) {
		return R.ok(service.lookup(query));
	}
}
