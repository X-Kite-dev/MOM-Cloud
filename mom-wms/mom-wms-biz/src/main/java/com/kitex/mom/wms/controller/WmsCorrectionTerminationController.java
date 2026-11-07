package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.WmsCorrectionTerminationDTO.*;
import com.kitex.mom.wms.dto.WmsFinishedGoodsCorrectionSubmissionDTO.Query;
import com.kitex.mom.wms.service.WmsCorrectionTerminationService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping({"/finishedGoodsReceiptCorrection/submissions", "/app/finishedGoodsReceiptCorrection/submissions"})
public class WmsCorrectionTerminationController {
	private final WmsCorrectionTerminationService service;

	@PostMapping("/outcome")
	@Operation(summary = "核对成品纠错申请登记或永久结束结果")
	public R<Outcome> lookup(@RequestBody Query query) {
		return R.ok(service.lookup(query));
	}

	@PostMapping("/end")
	@Operation(summary = "永久结束未受理成品纠错申请")
	public R<Outcome> end(@RequestBody EndCommand command) {
		return R.ok(service.end(command));
	}
}
