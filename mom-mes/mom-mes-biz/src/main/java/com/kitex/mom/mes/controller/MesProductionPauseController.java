package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesProductionPauseDTO;
import com.kitex.mom.mes.service.impl.MesProductionPauseService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 原生产工时权限下的暂停原因分析入口；无写入、授权或设备动作。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/production-time")
public class MesProductionPauseController {

	private final MesProductionPauseService service;

	/**
	 * 返回真实组织、原查询条件下的暂停原因及分页原区间；权限和范围均由服务再核。
	 */
	@GetMapping("/pauses")
	@PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
	public R<MesProductionPauseDTO.View> analyze(MesProductionPauseDTO.Query query) {
		return R.ok(service.analyze(query));
	}
}
