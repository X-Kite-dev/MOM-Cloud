package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesPickingWmsV1DTO;
import com.kitex.mom.mes.service.impl.MesPickingWmsFacade;
import com.kitex.mom.mes.service.impl.MesPickingWmsJournal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 本人领料V1准备、运输及本域核对；WMS受理不表示实发或签收。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWarehousePickingBill/wms/v1")
public class MesPickingWmsV1Controller {
	private final MesPickingWmsJournal journal;
	private final MesPickingWmsFacade facade;

	/**
	 * 当前准确领料及单一选定修订的原件，只读权限不授予命令资格。
	 */
	@GetMapping("/context")
	@PreAuthorize("@pms.hasPermission('mes_mesWarehousePickingBill_view','mes_mesWarehousePickingBill_edit')")
	public R<MesPickingWmsV1DTO.Context> context(@RequestParam Long billId,
	                                             @RequestParam(required = false) Integer revision) {
		return R.ok(journal.context(billId, revision));
	}

	/**
	 * 准备并可靠保存本域原命令；不运输WMS写请求。
	 */
	@PostMapping("/prepare")
	@PreAuthorize("@pms.hasPermission('mes_mesWarehousePickingBill_edit')")
	public R<MesPickingWmsV1DTO.Context> prepare(@RequestBody MesPickingWmsV1DTO.Prepare command) {
		return R.ok(facade.prepare(command));
	}

	/**
	 * 显式同号运输并核对原永久Proof，再应用本域投影。
	 */
	@PostMapping("/execute")
	@PreAuthorize("@pms.hasPermission('mes_mesWarehousePickingBill_edit')")
	public R<MesPickingWmsV1DTO.Context> execute(@RequestBody MesPickingWmsV1DTO.Request request) {
		return R.ok(facade.execute(request));
	}

	/**
	 * 不发WMS新写，但会记录回执和本域状态，仍为维护命令。
	 */
	@PostMapping("/reconcile")
	@PreAuthorize("@pms.hasPermission('mes_mesWarehousePickingBill_edit')")
	public R<MesPickingWmsV1DTO.Context> reconcile(@RequestBody MesPickingWmsV1DTO.Request request) {
		return R.ok(facade.reconcile(request));
	}
}
