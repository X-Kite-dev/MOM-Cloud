package com.kitex.mom.mes.controller;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mdm.dto.MdmRouteEngineeringDTO;
import com.kitex.mom.mdm.feign.RemoteMdmRouteEngineeringService;
import com.kitex.mom.mes.dto.MesInFlightSopChangeDTO;
import com.kitex.mom.mes.entity.MesInFlightSopChangeEntity;
import com.kitex.mom.mes.service.impl.MesInFlightSopChangeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 生产工单上的正式SOP在制变更。权限不授予岗位，也不代替异人审批。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesInFlightSopChange")
public class MesInFlightSopChangeController {
	private final MesInFlightSopChangeService service;
	private final RemoteMdmRouteEngineeringService engineering;

	/** 读取当前实际组织工单的完整节点及变更记录；不授予审批权。 */
	@GetMapping("/context")
	@PreAuthorize("@pms.hasPermission('mesTask:show') && (@pms.hasPermission('mesInFlightSopChange:propose') || "
			+ "@pms.hasPermission('mesInFlightSopChange:review'))")
	public R<Map<String, Object>> context(@RequestParam String taskCode) {
		return R.ok(service.context(taskCode));
	}

	/** 仅列同原路线/BOM的正式工程候选；实际提交仍由服务端精确取包核对SOP-only。 */
	@GetMapping("/engineering-candidates")
	@PreAuthorize("@pms.hasPermission('mesTask:show') && @pms.hasPermission('mesInFlightSopChange:propose')")
	public R<List<MdmRouteEngineeringDTO.ProductionCandidate>> candidates(@RequestParam String taskCode) {
		var task = service.selectionTask(taskCode);
		var response = engineering.productionCandidates(task.getMatCode());
		if (response == null || !response.isOk() || response.getData() == null || response.getData().size() > 100) {
			throw new CheckedException("批准工程候选不可完整核验，请稍后重试");
		}
		if (response.getData().stream().anyMatch(row -> row == null || row.getReleaseId() == null
				|| row.getRouteId() == null || row.getBomId() == null || !task.getMatCode().equals(row.getProductCode()))) {
			throw new CheckedException("批准工程候选身份或产品范围不完整");
		}
		return R.ok(response.getData().stream().filter(row -> !row.getReleaseId().equals(task.getEngineeringReleaseId())
				&& row.getRouteId().equals(task.getProcessRouteId()) && row.getBomId().equals(task.getBomId())).toList());
	}

	/** 按原号申请未接收稳定节点SOP变更；仅受理不代表已生效。 */
	@PostMapping
	@SysLog("申请未执行节点正式SOP变更")
	@PreAuthorize("@pms.hasPermission('mesTask:show') && @pms.hasPermission('mesInFlightSopChange:propose')")
	public R<MesInFlightSopChangeEntity> propose(@RequestBody MesInFlightSopChangeDTO.Proposal command) {
		return R.ok(service.propose(command));
	}

	/** 按原正文永久作废尚未受理的原号；已受理则返回原回执，不冒充取消成功。 */
	@PostMapping("/cancel-original")
	@SysLog("作废未受理的在制SOP变更原号")
	@PreAuthorize("@pms.hasPermission('mesTask:show') && @pms.hasPermission('mesInFlightSopChange:propose')")
	public R<Map<String, Object>> cancelOriginal(@RequestBody MesInFlightSopChangeDTO.Proposal command) {
		return R.ok(service.cancelOriginal(command));
	}

	/** 实名异人批准或驳回；批准前重核未执行范围及原包来源。 */
	@PostMapping("/{id}/review")
	@SysLog("异人复核未执行节点正式SOP变更")
	@PreAuthorize("@pms.hasPermission('mesTask:show') && @pms.hasPermission('mesInFlightSopChange:review')")
	public R<MesInFlightSopChangeEntity> review(@PathVariable Long id, @RequestBody MesInFlightSopChangeDTO.Review command) {
		return R.ok(service.review(id, command));
	}

	/** 仅原申请人按稳定原号恢复受理回执；不存在返回空，不生成替代申请。 */
	@GetMapping("/requests/{requestId}")
	@PreAuthorize("@pms.hasPermission('mesTask:show') && @pms.hasPermission('mesInFlightSopChange:propose')")
	public R<MesInFlightSopChangeEntity> receipt(@PathVariable String requestId) {
		return R.ok(service.receipt(requestId));
	}
}
