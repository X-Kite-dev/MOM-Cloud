package com.kitex.mom.wms.controller.app;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.WmsExternalInventoryLossDTO.*;
import com.kitex.mom.wms.service.impl.externalinventory.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.List;

/**
 * 手机端读取证件并办理盘亏；文件登记仅在Web开放，各动作按实际实名厂仓核权。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/inventoryPlan/external-loss")
public class AppExternalInventoryLossController {
	private final ExternalLossWorkflow workflow;
	private final ExternalLossCodec codec;
	private final ExternalLossQueries queries;

	/**
	 * 读取盘亏实际范围、实名新号和分动作资格。
	 */
	@GetMapping("/context")
	public R<Context<?>> contextOA1(@RequestParam String planDetailId) {
		return R.ok(workflow.context("OA1", planDetailId, true));
	}

	/**
	 * 盘亏准备可持久保存的规范原文。
	 */
	@PostMapping("/requests/prepare")
	public R<Envelope> prepareOA1(@RequestBody String raw) {
		return R.ok(workflow.prepare("OA1", codec.envelope(raw)));
	}

	/**
	 * 盘亏读取本人原号永久结果。
	 */
	@PostMapping("/requests/lookup")
	public R<Resolution<?>> lookupOA1(@RequestBody String raw) {
		return R.ok(workflow.lookup("OA1", codec.envelope(raw)));
	}

	/**
	 * 盘亏结束未受理号码。
	 */
	@PostMapping("/requests/end")
	public R<Resolution<?>> endOA1(@RequestBody String raw) {
		return R.ok(workflow.mutate("OA1", codec.envelope(raw), true, true, null));
	}

	/**
	 * 盘亏按原文执行首次业务动作。
	 */
	@PostMapping("/requests/execute")
	public R<Resolution<?>> executeOA1(@RequestBody String raw) {
		return R.ok(workflow.mutate("OA1", codec.envelope(raw), true, false, null));
	}

	/**
	 * 读取整个盘点范围的真实复盘、货主与授权依据。
	 */
	@GetMapping("/basis")
	public R<Basis> basis(@RequestParam String planDetailId) {
		return R.ok(queries.basis(planDetailId));
	}

	/**
	 * 分页读取指定实物及实际可扣量。
	 */
	@GetMapping("/candidates")
	public R<Candidates> candidates(@RequestParam String planDetailId,
	                                @RequestParam(required = false) String afterStockId) {
		return R.ok(queries.candidates(planDetailId, afterStockId));
	}

	/**
	 * 读取真实审批迁移与当时出库回执。
	 */
	@GetMapping("/history")
	public R<History> history(@RequestParam String planDetailId,
	                          @RequestParam(required = false) String beforeEffectId) {
		return R.ok(queries.history(planDetailId, beforeEffectId));
	}

	/**
	 * 读取本人可选与审核可见的证件元数据。
	 */
	@GetMapping("/evidence/list")
	public R<List<Evidence>> evidence(@RequestParam String planDetailId) {
		return R.ok(queries.evidence(planDetailId));
	}

	/**
	 * 按原件归属重新核验下载权、长度及摘要。
	 */
	@GetMapping("/evidence/{id}/file")
	public ResponseEntity<byte[]> file(@PathVariable("id") String evidenceId) {
		return queries.file(evidenceId);
	}

	/**
	 * 读取同仓可见的原申请逐实物分配及实收来源，供审批、实发前核对或追溯。
	 */
	@GetMapping("/allocations")
	public R<FrozenAllocations> allocations(@RequestParam String planDetailId, @RequestParam String approvalId) {
		return R.ok(queries.allocations(planDetailId, approvalId));
	}
}
