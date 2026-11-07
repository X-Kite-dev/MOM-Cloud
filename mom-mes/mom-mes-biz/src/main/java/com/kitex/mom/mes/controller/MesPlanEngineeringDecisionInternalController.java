package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesPlanEngineeringDecisionDTO;
import com.kitex.mom.mes.service.impl.MesPlanEngineeringDecisionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PLAN原号确认与撤销的实名内部入口，内部标记不能替代原岗位和实际工厂授权。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/plan-engineering-decision/internal")
@PreAuthorize("@pms.hasPermission('mesTask:show') && @pms.hasPermission('pln_plnIssue_view') && "
		+ "(@pms.hasPermission('mesInFlightSopChange:propose') || @pms.hasPermission('mesInFlightSopChange:review'))")
public class MesPlanEngineeringDecisionInternalController {

	private final MesPlanEngineeringDecisionService service;

	/**
	 * 精确读取MES实际永久终态，空回执始终是未知。
	 */
	@Inner
	@GetMapping("/request")
	public R<MesPlanEngineeringDecisionDTO.Receipt> request(@RequestParam String requestId) {
		return R.ok(service.request(requestId));
	}

	/**
	 * 与采用同一PEP串行；采用先赢时只返回原APPLIED，不追加撤销。
	 */
	@Inner
	@PostMapping("/cancel")
	@PreAuthorize("@pms.hasPermission('mesTask:show') && @pms.hasPermission('pln_plnIssue_view') && "
			+ "@pms.hasPermission('pln_plnIssue_edit') && (@pms.hasPermission('mesInFlightSopChange:propose') || "
			+ "@pms.hasPermission('mesInFlightSopChange:review'))")
	public R<MesPlanEngineeringDecisionDTO.Receipt> cancel(@RequestBody MesPlanEngineeringDecisionDTO.Cancel command) {
		return R.ok(service.cancel(command));
	}
}
