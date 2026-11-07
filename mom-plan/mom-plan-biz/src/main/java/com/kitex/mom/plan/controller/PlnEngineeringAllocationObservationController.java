package com.kitex.mom.plan.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.plan.dto.PlnEngineeringAllocationDTO;
import com.kitex.mom.plan.service.impl.PlnEngineeringAllocationObservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PLAN自有分配依据实名读取入口，内部标记和原计划/工单查看权同时必需。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/engineering-allocation/internal")
public class PlnEngineeringAllocationObservationController {

	private final PlnEngineeringAllocationObservationService service;

	/**
	 * 不接收外部证据正文，不产生准备、审批、采用或解除占用等写入。
	 */
	@Inner
	@PreAuthorize("@pms.hasPermission('pln_plnIssue_view') && @pms.hasPermission('mesTask:show')")
	@GetMapping("/observation")
	public R<PlnEngineeringAllocationDTO.Observation> observe(@RequestParam String taskCode) {
		return R.ok(service.observe(taskCode));
	}
}
