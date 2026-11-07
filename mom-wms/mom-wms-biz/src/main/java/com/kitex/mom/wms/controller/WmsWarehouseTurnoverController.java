package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.WmsWarehouseTurnoverDTO.Context;
import com.kitex.mom.wms.dto.WmsWarehouseTurnoverDTO.View;
import com.kitex.mom.wms.service.WmsWarehouseTurnoverService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 按当前组织和仓库读取真实历史库存数量周转。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsWarehouseTurnover")
public class WmsWarehouseTurnoverController {
	private final WmsWarehouseTurnoverService service;

	@GetMapping("/context")
	public R<Context> context(@RequestParam String warehouseCode) {
		return R.ok(service.context(warehouseCode));
	}

	@GetMapping
	public R<View> query(@RequestParam String warehouseCode,
	                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
	                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
	                     @RequestParam(defaultValue = "BOTH") String basis, @RequestParam(required = false) String matCode) {
		return R.ok(service.query(warehouseCode, fromDate, toDate, basis, matCode));
	}
}
