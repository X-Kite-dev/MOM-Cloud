package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.HasPermission;
import com.kitex.mom.wms.dto.WmsWarehouseCapacityDTO.View;
import com.kitex.mom.wms.dto.WmsWarehouseCapacityDTO.SaveCommand;
import com.kitex.mom.wms.dto.WmsWarehouseCapacityDTO.Saved;
import com.kitex.mom.wms.dto.WmsWarehouseCapacityDTO.Detail;
import com.kitex.mom.wms.service.WmsWarehouseCapacityService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仓库容量配置、实算与物料占用贡献。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsWarehouseCapacity")
public class WmsWarehouseCapacityController {
	private final WmsWarehouseCapacityService service;

	@GetMapping
	@Operation(summary = "读取仓库容量和实际占用")
	public R<View> load(@RequestParam String warehouseCode) {
		return R.ok(service.load(warehouseCode));
	}

	@PostMapping
	@HasPermission("wms_wmsWarehouseMenu_add")
	@SysLog("保存仓库容量配置")
	public R<Saved> save(@RequestBody SaveCommand command) {
		return R.ok(service.save(command));
	}

	@GetMapping("/details")
	public R<Detail> details(@RequestParam String warehouseCode, @RequestParam String locationCode,
	                         @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
		return R.ok(service.details(warehouseCode, locationCode, page, size));
	}
}
