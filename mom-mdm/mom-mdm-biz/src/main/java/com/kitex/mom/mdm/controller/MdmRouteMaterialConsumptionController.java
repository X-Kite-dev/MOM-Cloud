package com.kitex.mom.mdm.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.dto.MdmRouteMaterialConsumptionDTO;
import com.kitex.mom.mdm.service.MdmRouteMaterialConsumptionReader;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 实际工厂稳定路线节点的原耗料配置读取入口，沿既有路线权限和域服务校验。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmProcessRoute/material-consumption")
public class MdmRouteMaterialConsumptionController {
	private final MdmRouteMaterialConsumptionReader reader;

	/**
	 * 在已有路线许可内读取实际工厂已保存节点的耗料配置，空设置不补默认值。
	 *
	 * @param nodeId 已保存的稳定路线节点ID字符串
	 * @return 原设置、原绑定与实际BOM候选；无权、损坏或超限时拒绝
	 */
	@GetMapping("/{nodeId}")
	@PreAuthorize("@pms.hasPermission('mdm_mdmProcessRoute_view','mdm_mdmProcessRoute_edit')")
	public R<MdmRouteMaterialConsumptionDTO.View> inspect(@PathVariable String nodeId) {
		return R.ok(reader.inspect(nodeId));
	}
}
