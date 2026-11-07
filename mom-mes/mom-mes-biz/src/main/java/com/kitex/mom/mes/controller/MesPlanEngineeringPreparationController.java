package com.kitex.mom.mes.controller;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import com.kitex.mom.plan.dto.PlnEngineeringPreparationDTO.*;
import com.kitex.mom.plan.feign.RemotePlanEngineeringPreparationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MES原工程影响页的PLAN准备代理，不开启本地数据库事务，也不执行MES采用或改状态。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesInFlightEngineeringImpact/preparation")
@PreAuthorize("@pms.hasPermission('mesTask:show') && @pms.hasPermission('pln_plnIssue_view') && "
		+ "(@pms.hasPermission('mesInFlightSopChange:propose') || @pms.hasPermission('mesInFlightSopChange:review'))")
public class MesPlanEngineeringPreparationController {

	private final MesActualFactoryAccessService access;
	private final RemotePlanEngineeringPreparationService plans;

	/**
	 * 不将未安装结构返回为空业务。
	 */
	@GetMapping("/context")
	public R<Context> context(@RequestParam String taskCode) {
		access.require("read");
		return R.ok(data(plans.context(taskCode)));
	}

	/**
	 * 原号可能尚未受理，返回真实null供原号恢复。
	 */
	@GetMapping("/request")
	public R<Receipt> request(@RequestParam String requestId) {
		access.require("read");
		return R.ok(data(plans.request(requestId)));
	}

	/**
	 * 仅代理两域真实授权的PLAN来源保护，成功不是工程采用。
	 */
	@PostMapping("/prepare")
	@PreAuthorize("@pms.hasPermission('mesTask:show') && @pms.hasPermission('pln_plnIssue_view') && "
			+ "@pms.hasPermission('pln_plnIssue_edit') && @pms.hasPermission('mesInFlightSopChange:propose')")
	public R<Receipt> prepare(@RequestBody Command command) {
		access.require("write");
		return R.ok(data(plans.prepare(command)));
	}

	/**
	 * 撤销原准备，原始工程及历史事实保持。
	 */
	@PostMapping("/cancel")
	@PreAuthorize("@pms.hasPermission('mesTask:show') && @pms.hasPermission('pln_plnIssue_view') && "
			+ "@pms.hasPermission('pln_plnIssue_edit') && (@pms.hasPermission('mesInFlightSopChange:propose') || "
			+ "@pms.hasPermission('mesInFlightSopChange:review'))")
	public R<Receipt> cancel(@RequestBody CancelCommand command) {
		access.require("write");
		return R.ok(data(plans.cancel(command)));
	}

	/**
	 * 只带原号继续确认，不接受前端传入的MES成功事实。
	 */
	@PostMapping("/finish")
	@PreAuthorize("@pms.hasPermission('mesTask:show') && @pms.hasPermission('pln_plnIssue_view') && "
			+ "@pms.hasPermission('pln_plnIssue_edit') && (@pms.hasPermission('mesInFlightSopChange:propose') || "
			+ "@pms.hasPermission('mesInFlightSopChange:review'))")
	public R<Receipt> finish(@RequestParam String requestId) {
		access.require("write");
		return R.ok(data(plans.finish(requestId)));
	}

	private <T> T data(R<T> response) {
		if (response == null || !response.isOk()) {
			throw new CheckedException(response == null ? "PLAN原号结果未确认，请按原号核对" : response.getMsg());
		}
		return response.getData();
	}
}
