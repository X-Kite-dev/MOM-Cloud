package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.WmsExternalInventoryLossDTO.*;
import com.kitex.mom.wms.service.impl.externalinventory.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

/**
 * 外部盘亏、仓级授权及企业原件的Web入口，各动作按实际实名厂仓核权。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsInsideInventoryPlan/external-loss")
public class WmsExternalInventoryLossController {
	private final ExternalLossWorkflow workflow;
	private final ExternalLossCodec codec;
	private final ExternalLossQueries queries;

	/**
	 * 读取盘亏实际范围、实名新号和分动作资格。
	 */
	@GetMapping("/context")
	public R<Context<?>> contextOA1(@RequestParam String planDetailId) {
		return R.ok(workflow.context("OA1", planDetailId, false));
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
		return R.ok(workflow.mutate("OA1", codec.envelope(raw), false, true, null));
	}

	/**
	 * 盘亏按原文执行首次业务动作。
	 */
	@PostMapping("/requests/execute")
	public R<Resolution<?>> executeOA1(@RequestBody String raw) {
		return R.ok(workflow.mutate("OA1", codec.envelope(raw), false, false, null));
	}

	/**
	 * 读取仓级授权实际范围、实名新号和分动作资格。
	 */
	@GetMapping("/mandates/context")
	public R<Context<?>> contextOM1(@RequestParam String warehouseCode) {
		return R.ok(workflow.context("OM1", warehouseCode, false));
	}

	/**
	 * 仓级授权准备可持久保存的规范原文。
	 */
	@PostMapping("/mandates/prepare")
	public R<Envelope> prepareOM1(@RequestBody String raw) {
		return R.ok(workflow.prepare("OM1", codec.envelope(raw)));
	}

	/**
	 * 仓级授权读取本人原号永久结果。
	 */
	@PostMapping("/mandates/lookup")
	public R<Resolution<?>> lookupOM1(@RequestBody String raw) {
		return R.ok(workflow.lookup("OM1", codec.envelope(raw)));
	}

	/**
	 * 仓级授权结束未受理号码。
	 */
	@PostMapping("/mandates/end")
	public R<Resolution<?>> endOM1(@RequestBody String raw) {
		return R.ok(workflow.mutate("OM1", codec.envelope(raw), false, true, null));
	}

	/**
	 * 仓级授权按原文执行首次业务动作。
	 */
	@PostMapping("/mandates/execute")
	public R<Resolution<?>> executeOM1(@RequestBody String raw) {
		return R.ok(workflow.mutate("OM1", codec.envelope(raw), false, false, null));
	}

	/**
	 * 读取企业证件实际范围、实名新号和分动作资格。
	 */
	@GetMapping("/evidence/context")
	public R<Context<?>> contextOE1(@RequestParam String planDetailId) {
		return R.ok(workflow.context("OE1", planDetailId, false));
	}

	/**
	 * 企业证件准备可持久保存的规范原文。
	 */
	@PostMapping("/evidence/prepare")
	public R<Envelope> prepareOE1(@RequestBody String raw) {
		return R.ok(workflow.prepare("OE1", codec.envelope(raw)));
	}

	/**
	 * 企业证件读取本人原号永久结果。
	 */
	@PostMapping("/evidence/lookup")
	public R<Resolution<?>> lookupOE1(@RequestBody String raw) {
		return R.ok(workflow.lookup("OE1", codec.envelope(raw)));
	}

	/**
	 * 企业证件结束未受理号码。
	 */
	@PostMapping("/evidence/end")
	public R<Resolution<?>> endOE1(@RequestBody String raw) {
		return R.ok(workflow.mutate("OE1", codec.envelope(raw), false, true, null));
	}

	/**
	 * 企业证件按原文执行首次业务动作。
	 */
	@PostMapping(value = "/evidence/execute", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public R<Resolution<?>> executeOE1(@RequestParam String requestId, @RequestParam String commandText,
	                                   @RequestPart("file") MultipartFile file) {
		return R.ok(workflow.mutate("OE1", new Envelope(requestId, commandText), false, false, file));
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
	 * 读取本仓外部货主授权历史。
	 */
	@GetMapping("/mandates/history")
	public R<MandatePage> mandates(@RequestParam String warehouseCode,
	                               @RequestParam(required = false) String beforeMandateId) {
		return R.ok(queries.mandates(warehouseCode, beforeMandateId));
	}

	/**
	 * 在本仓查看权下检索实名货主。
	 */
	@GetMapping("/mandates/owners")
	public R<List<Owner>> owners(@RequestParam String warehouseCode, @RequestParam String ownerType,
	                             @RequestParam String keyword) {
		return R.ok(queries.owners(warehouseCode, ownerType, keyword));
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
