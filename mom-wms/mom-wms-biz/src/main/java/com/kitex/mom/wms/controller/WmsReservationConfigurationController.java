package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsReservationConfigurationDTO.*;
import com.kitex.mom.wms.service.WmsReservationConfigurationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsReservationMovementPolicy/configuration")
public class WmsReservationConfigurationController {
	private final WmsReservationConfigurationService service;

	@GetMapping
	@PreAuthorize("@pms.hasPermission('wms_reservationMovementPolicy_view')")
	public R<Snapshot> read() {
		return R.ok(service.read());
	}

	@PostMapping
	@SysLog("保存带内容版本的WMS预留策略")
	@PreAuthorize("@pms.hasPermission('wms_reservationMovementPolicy_view')"
			+ " && @pms.hasPermission('wms_reservationMovementPolicy_edit')")
	public R<Saved> save(@RequestBody Save command) {
		return R.ok(service.save(command));
	}
}
