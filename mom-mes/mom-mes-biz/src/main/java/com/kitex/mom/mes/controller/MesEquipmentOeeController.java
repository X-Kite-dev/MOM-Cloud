package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesEquipmentOeeDTO.*;
import com.kitex.mom.mes.service.impl.MesEquipmentOeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Explicit operator-confirmed import; not a device-authenticated ingestion gateway.
 */

/**
 * 设备OEE来源预览、受理及原件查询入口；复用既有权限，领域规则由服务核验。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/equipment-oee")
public class MesEquipmentOeeController {
	private final MesEquipmentOeeService service;

	/**
	 * 只读核对完整来源并返回预览；来源结构、原单位或模板指纹不符时拒绝，不产生成功保存事实。
	 */
	@PostMapping(value = "/preview", consumes = "application/json")
	@PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
	public R<Saved> preview(@RequestBody ImportCommand command) {
		return R.ok(service.preview(command == null ? null : command.sourceJson()));
	}

	/**
	 * 按实际组织永久原批次号保存完整OEE来源；同号异事实、矛盾区间或必要写入失败时拒绝。
	 */
	@PostMapping(value = "/batches", consumes = "application/json")
	@PreAuthorize("@pms.hasPermission('mesTaskArrange:arrange')")
	public R<Saved> importBatch(@RequestBody ImportCommand command) {
		return R.ok(service.importBatch(command == null ? null : command.sourceJson()));
	}

	/**
	 * 按当前实际工厂的精确原批次号读取不可变来源并复算；缺失或摘要不符不返回健康指标。
	 */
	@GetMapping("/batch")
	@PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
	public R<Saved> get(@RequestParam String batchId) {
		return R.ok(service.get(batchId));
	}

	/**
	 * 按实际组织、设备条件及有界页码读取原件目录；目录不携带大原文，非法分页拒绝。
	 */
	@GetMapping("/batches")
	@PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
	public R<Page> page(@RequestParam(required = false) String deviceCode,
	                    @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int size) {
		return R.ok(service.page(deviceCode, page, size));
	}
}
