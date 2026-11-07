package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.service.impl.MesPlanEngineeringAllocationObservationService;
import com.kitex.mom.plan.dto.PlnEngineeringAllocationDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 原工程影响入口的PLAN依据代理，保留两域原权限，不增加任何授权或生效操作。
 */
@RestController
@RequiredArgsConstructor
public class MesPlanEngineeringAllocationObservationController {

	private final MesPlanEngineeringAllocationObservationService service;

	/**
	 * PLAN依据独立按需读取，失败不影响阅读原工程差异，也不改旧Preview响应。
	 */
	@GetMapping("/mesInFlightEngineeringImpact/plan-allocation")
	@PreAuthorize("@pms.hasPermission('mesTask:show') && @pms.hasPermission('pln_plnIssue_view') && "
			+
			"(@pms.hasPermission('mesInFlightSopChange:propose') || @pms.hasPermission('mesInFlightSopChange:review'))")
	public R<PlnEngineeringAllocationDTO.Observation> observe(@RequestParam String taskCode) {
		return R.ok(service.observe(taskCode));
	}
}
