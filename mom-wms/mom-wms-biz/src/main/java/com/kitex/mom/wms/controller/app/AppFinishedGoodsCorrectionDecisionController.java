package com.kitex.mom.wms.controller.app;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.WmsFinishedGoodsCorrectionDecisionDTO.Query;
import com.kitex.mom.wms.dto.WmsFinishedGoodsCorrectionDecisionDTO.Receipt;
import com.kitex.mom.wms.dto.WmsFinishedGoodsCorrectionDecisionDTO.EndCommand;
import com.kitex.mom.wms.dto.WmsFinishedGoodsCorrectionDecisionDTO.Outcome;
import com.kitex.mom.wms.service.WmsFinishedGoodsCorrectionDecisionService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端纠错办理原号的登记查询与安全结束，服务按原动作及冻结仓库核权限。
 * 保留旧类名和V1路径兼容；本控制器不开放PDA审核或实物执行。
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

	/**
	 * 按完整原文查询登记或结束凭据；不重新执行批准、驳回或撤回。
	 */
	@PostMapping("/outcome")
	@Operation(summary = "核对成品纠错原办理登记或结束结果")
	public R<Outcome> outcome(@RequestBody Query query) {
		return R.ok(service.outcome(query));
	}

	/**
	 * 结束只封存未执行原号，原办理已提交时返回实际登记结果。
	 */
	@PostMapping("/end")
	@Operation(summary = "安全结束未执行的成品纠错原办理请求")
	public R<Outcome> end(@RequestBody EndCommand command) {
		return R.ok(service.end(command));
	}
}
