package com.kitex.mom.mes.controller;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mdm.dto.MdmRouteEngineeringDTO;
import com.kitex.mom.mdm.feign.RemoteMdmRouteEngineeringService;
import com.kitex.mom.mes.dto.MesManualLaborChangeDTO;
import com.kitex.mom.mes.entity.MesManualLaborChangeEntity;
import com.kitex.mom.mes.service.impl.MesManualLaborStandardChangeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 独立手工工单标准工时申请与异审；不赋予岗位权限，不改PLAN或原工程。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesManualLaborChange")
public class MesManualLaborChangeController {
	private final MesManualLaborStandardChangeService service;
	private final RemoteMdmRouteEngineeringService engineering;

	/**
	 * 读取本组织原独立手工工单完整节点和申请记录；不授予变更资格。
	 */
	@GetMapping("/context")
	@PreAuthorize("@pms.hasPermission('mesTask:show') && (@pms.hasPermission('mesManualLaborChange:propose') || @pms.hasPermission('mesManualLaborChange:review'))")
	public R<Map<String, Object>> context(@RequestParam String taskCode) {
		return R.ok(service.context(taskCode));
	}

	/**
	 * 列出当前产品同原路线/BOM的正式版本；提交仍逐字段核标准工时唯一允许维度。
	 */
	@GetMapping("/engineering-candidates")
	@PreAuthorize("@pms.hasPermission('mesTask:show') && @pms.hasPermission('mesManualLaborChange:propose')")
	public R<List<MdmRouteEngineeringDTO.ProductionCandidate>> candidates(@RequestParam String taskCode) {
		var task = service.selectionTask(taskCode);
		var response = engineering.productionCandidates(task.getMatCode());
		if (response == null || !response.isOk() || response.getData() == null || response.getData().size() > 100
				|| response.getData().stream().anyMatch(row -> row == null || row.getReleaseId() == null
				|| row.getRouteId() == null || row.getBomId() == null || row.getContentHash() == null
				|| !row.getContentHash().matches("[0-9a-f]{64}") || !task.getMatCode().equals(row.getProductCode()))) {
			throw new CheckedException("正式工程候选不可完整核验，请稍后重试");
		}
		return R.ok(response.getData().stream().filter(row -> !row.getReleaseId().equals(task.getEngineeringReleaseId())
						&& row.getRouteId().equals(task.getProcessRouteId()) && row.getBomId().equals(task.getBomId()))
				.toList());
	}

	/**
	 * 准备申请时只读核对原标准与正式目标本节点标准；不产生申请或批准资格。
	 */
	@GetMapping("/preview")
	@PreAuthorize("@pms.hasPermission('mesTask:show') && @pms.hasPermission('mesManualLaborChange:propose')")
	public R<MesManualLaborChangeDTO.Comparison> preview(@RequestParam String taskCode, @RequestParam String processId,
	                                                     @RequestParam String releaseId,
	                                                     @RequestParam String contentHash) {
		return R.ok(service.preview(taskCode, processId, releaseId, contentHash));
	}

	/**
	 * 原号申请本节点标准工时批准层；受理不等于批准。
	 */
	@PostMapping
	@SysLog("申请独立手工工单标准工时变更")
	@PreAuthorize("@pms.hasPermission('mesTask:show') && @pms.hasPermission('mesManualLaborChange:propose')")
	public R<MesManualLaborChangeEntity> propose(@RequestBody MesManualLaborChangeDTO.Proposal command) {
		return R.ok(service.propose(command));
	}

	/**
	 * 永久作废未受理原号；已受理返回原申请，不能冒充已取消批准业务。
	 */
	@PostMapping("/cancel-original")
	@SysLog("作废独立手工工单标准工时原号")
	@PreAuthorize("@pms.hasPermission('mesTask:show') && @pms.hasPermission('mesManualLaborChange:propose')")
	public R<MesManualLaborChangeEntity> cancel(@RequestBody MesManualLaborChangeDTO.Proposal command) {
		return R.ok(service.cancel(command));
	}

	/**
	 * 异人复核完整原范围；已审批仅支持同人同决定同依据重放。
	 */
	@PostMapping("/{id}/review")
	@SysLog("异人复核独立手工工单标准工时")
	@PreAuthorize("@pms.hasPermission('mesTask:show') && @pms.hasPermission('mesManualLaborChange:review')")
	public R<MesManualLaborChangeEntity> review(@PathVariable Long id,
	                                            @RequestBody MesManualLaborChangeDTO.Review command) {
		return R.ok(service.review(id, command));
	}

	/**
	 * 原申请人核对稳定原号；无回执时保留原号，不创建替代申请。
	 */
	@GetMapping("/requests/{requestId}")
	@PreAuthorize("@pms.hasPermission('mesTask:show') && @pms.hasPermission('mesManualLaborChange:propose')")
	public R<MesManualLaborChangeEntity> receipt(@PathVariable String requestId) {
		return R.ok(service.receipt(requestId));
	}
}
