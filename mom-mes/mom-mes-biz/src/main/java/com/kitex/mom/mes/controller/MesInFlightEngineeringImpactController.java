package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.dto.MdmRouteEngineeringDTO;
import com.kitex.mom.mes.dto.MesInFlightEngineeringImpactDTO;
import com.kitex.mom.mes.service.impl.MesInFlightEngineeringImpactService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 既有SOP工作台的工程影响只读入口，沿用真实岗位和工单读取权限，不开放执行命令。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesInFlightEngineeringImpact")
@PreAuthorize("@pms.hasPermission('mesTask:show') && (@pms.hasPermission('mesInFlightSopChange:propose') || "
		+ "@pms.hasPermission('mesInFlightSopChange:review'))")
public class MesInFlightEngineeringImpactController {
	private final MesInFlightEngineeringImpactService service;

	/** 当前工单产品的正式生产工程候选；允许BOM或路线变化用于只读影响比较。 */
	@GetMapping("/candidates")
	public R<List<MdmRouteEngineeringDTO.ProductionCandidate>> candidates(@RequestParam String taskCode) {
		return R.ok(service.candidates(taskCode));
	}

	/** 仅接收工单号和正式版本对，正文及跨域证据不从请求接收；缺原件时明确失败。 */
	@GetMapping("/preview")
	public R<MesInFlightEngineeringImpactDTO.Preview> preview(@RequestParam String taskCode,
			@RequestParam String releaseId, @RequestParam String contentHash) {
		return R.ok(service.preview(taskCode, releaseId, contentHash));
	}
}
