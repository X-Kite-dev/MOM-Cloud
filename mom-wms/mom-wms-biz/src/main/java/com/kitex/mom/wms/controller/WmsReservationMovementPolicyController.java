package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.entity.WmsReservationMovementPolicyEntity;
import com.kitex.mom.wms.service.WmsReservationMovementPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsReservationMovementPolicy")
@Tag(description = "wmsReservationMovementPolicy", name = "WMS作业类型预留策略")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsReservationMovementPolicyController {
	private final WmsReservationMovementPolicyService service;

	@Operation(summary = "查询当前工厂的作业类型预留策略")
	@GetMapping("/list")
	@PreAuthorize("@pms.hasPermission('wms_reservationMovementPolicy_view')")
	public R<List<WmsReservationMovementPolicyEntity>> list() {
		return R.ok(service.listPolicies());
	}

	@Operation(summary = "试算当前工厂作业类型的预留规则")
	@GetMapping("/preview")
	@PreAuthorize("@pms.hasPermission('wms_reservationMovementPolicy_view')")
	public R<WmsReservationMovementPolicyService.Evaluation> preview(@RequestParam String movementType) {
		return R.ok(service.preview(movementType));
	}

	@Operation(summary = "保存或停用作业类型预留策略")
	@PostMapping
	@SysLog("保存WMS作业类型预留策略")
	@PreAuthorize("@pms.hasPermission('wms_reservationMovementPolicy_edit')")
	public R<WmsReservationMovementPolicyEntity> save(
			@RequestBody WmsReservationMovementPolicyEntity policy) {
		throw new com.kitex.common.core.exception.CheckedException("请更新客户端并先读取配置版本后保存预留策略");
	}
}
