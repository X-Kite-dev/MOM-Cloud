package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.wms.service.WmsWarehouseLifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsWarehouseLifecycle")
public class WmsWarehouseLifecycleController {

	private final WmsWarehouseLifecycleService service;

	@Inner
	@GetMapping("/references")
	public R<List<String>> references(@RequestParam String warehouseCode) {
		return R.ok(service.references(warehouseCode));
	}

	@Inner
	@GetMapping("/businessReferences")
	public R<List<String>> businessReferences(@RequestParam String warehouseCode) {
		return R.ok(service.businessReferences(warehouseCode));
	}

	@Inner
	@PostMapping("/cleanup")
	public R<Boolean> cleanup(@RequestParam String warehouseCode) {
		return R.ok(service.cleanup(warehouseCode));
	}
}
