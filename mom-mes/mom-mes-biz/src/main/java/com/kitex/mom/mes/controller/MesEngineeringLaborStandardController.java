package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesEngineeringLaborStandardDTO;
import com.kitex.mom.mes.service.impl.MesEngineeringLaborStandardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 原工程标准工时只读入口，沿用工单查看及实际工厂读权限。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesEngineeringLaborStandard")
public class MesEngineeringLaborStandardController {
	private final MesEngineeringLaborStandardService service;

	/** 按当前真实组织工单读取原标准；缺历史来源明确未知，损坏原件明确拒绝。 */
	@GetMapping
	@PreAuthorize("@pms.hasPermission('mesTask:show')")
	public R<MesEngineeringLaborStandardDTO.View> view(@RequestParam String taskCode) {
		return R.ok(service.view(taskCode));
	}
}
