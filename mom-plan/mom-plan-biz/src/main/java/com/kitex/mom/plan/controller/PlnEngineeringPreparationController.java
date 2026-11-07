package com.kitex.mom.plan.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.plan.dto.PlnEngineeringPreparationDTO.*;
import com.kitex.mom.plan.service.impl.PlnEngineeringPreparationService;
import com.kitex.mom.plan.service.impl.PlnEngineeringResolutionService;
import com.kitex.mom.plan.dto.PlnEngineeringResolutionDTO.Certificate;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PLAN准备的原岗位实名入口，内部标记不代替用户身份或权限。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/engineering-preparation/internal")
@PreAuthorize("@pms.hasPermission('mesTask:show') && @pms.hasPermission('pln_plnIssue_view') && "
		+ "(@pms.hasPermission('mesInFlightSopChange:propose') || @pms.hasPermission('mesInFlightSopChange:review'))")
public class PlnEngineeringPreparationController {

	private final PlnEngineeringPreparationService service;
	private final PlnEngineeringResolutionService resolutions;

	/**
	 * 同组织原工单最近记录。
	 */
	@Inner
	@GetMapping("/context")
	public R<Context> context(@RequestParam String taskCode) {
		return R.ok(service.context(taskCode));
	}

	/**
	 * 同组织原号读取，不创建新业务。
	 */
	@Inner
	@GetMapping("/request")
	public R<Receipt> request(@RequestParam String requestId) {
		return R.ok(service.request(requestId));
	}

	/**
	 * 原计划编辑与工程编制岗位共同具备，来源由服务端重新读取。
	 */
	@Inner
	@PostMapping("/prepare")
	@PreAuthorize("@pms.hasPermission('mesTask:show') && @pms.hasPermission('pln_plnIssue_view') && "
			+ "@pms.hasPermission('pln_plnIssue_edit') && @pms.hasPermission('mesInFlightSopChange:propose')")
	public R<Receipt> prepare(@RequestBody Command command) {
		return R.ok(service.prepare(command));
	}

	/**
	 * 计划编辑岗位且具备原编制/审核权，异人撤销还在领域层核审核岗位。
	 */
	@Inner
	@PostMapping("/cancel")
	@PreAuthorize("@pms.hasPermission('mesTask:show') && @pms.hasPermission('pln_plnIssue_view') && "
			+ "@pms.hasPermission('pln_plnIssue_edit') && (@pms.hasPermission('mesInFlightSopChange:propose') || "
			+ "@pms.hasPermission('mesInFlightSopChange:review'))")
	public R<Receipt> cancel(@RequestBody CancelCommand command) {
		return R.ok(resolutions.cancel(command));
	}

	/**
	 * 原号权威凭据不猜工单身份，动态终结状态不改变原authority摘要。
	 */
	@Inner
	@GetMapping("/certificate")
	public R<Certificate> certificate(@RequestParam String requestId) {
		return R.ok(service.certificate(requestId));
	}

	/**
	 * 仅按原号从MES取真实永久决策并确认，结果未知不解除来源保护。
	 */
	@Inner
	@PostMapping("/finish")
	@PreAuthorize("@pms.hasPermission('mesTask:show') && @pms.hasPermission('pln_plnIssue_view') && "
			+ "@pms.hasPermission('pln_plnIssue_edit') && (@pms.hasPermission('mesInFlightSopChange:propose') || "
			+ "@pms.hasPermission('mesInFlightSopChange:review'))")
	public R<Receipt> finish(@RequestParam String requestId) {
		return R.ok(resolutions.finish(requestId));
	}
}
