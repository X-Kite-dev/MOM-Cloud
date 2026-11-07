package com.kitex.mom.wms.controller.app;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.WmsInventoryApprovalRecoveryDTO.*;
import com.kitex.mom.wms.service.inventoryapproval.WmsInventoryApprovalRecoveryService;
import com.kitex.mom.wms.service.inventoryapproval.WmsInventoryApprovalRecoveryCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 手机盘点审批原号恢复；认证、实际厂仓岗位与事务仲裁统一在服务层。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/inventoryPlan/adjustment-approvals/recovery")
public class AppInventoryApprovalRecoveryController {
	private final WmsInventoryApprovalRecoveryService service;
	private final WmsInventoryApprovalRecoveryCodec codec;

	/**
	 * 获取真实身份与原盘点元数据，历史核对不受当前作业状态限制。
	 */
	@GetMapping("/context")
	public R<Context> context(@RequestParam String planDetailId) {
		return R.ok(service.context(planDetailId, true));
	}

	/**
	 * 首存前静态规范化，不产生审批业务。
	 */
	@PostMapping("/prepare")
	public R<Envelope> prepare(@RequestBody String raw) {
		return R.ok(service.prepare(codec.envelope(raw)));
	}

	/**
	 * 使用已可靠首存的规范原号与原文执行一次真实动作。
	 */
	@PostMapping("/execute")
	public R<Resolution> execute(@RequestBody String raw) {
		return R.ok(service.execute(codec.envelope(raw), true));
	}

	/**
	 * 核对本人原文对应的永久结果。
	 */
	@PostMapping("/lookup")
	public R<Resolution> lookup(@RequestBody String raw) {
		return R.ok(service.lookup(codec.envelope(raw)));
	}

	/**
	 * 只结束未受理号，已经发生的审批动作不撤销。
	 */
	@PostMapping("/end")
	public R<Resolution> end(@RequestBody String raw) {
		return R.ok(service.end(codec.envelope(raw)));
	}
}
