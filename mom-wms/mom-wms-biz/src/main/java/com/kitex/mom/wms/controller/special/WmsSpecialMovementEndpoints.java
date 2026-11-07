package com.kitex.mom.wms.controller.special;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsSpecialMovementDTO.*;
import com.kitex.mom.wms.service.special.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 两端共用业务合同，移动身份只由具体服务端路由确定。
 */
public abstract class WmsSpecialMovementEndpoints {
	protected final WmsSpecialMovementService service;
	protected final WmsSpecialPolicyService policies;
	protected final WmsSpecialDirectoryService directory;

	protected WmsSpecialMovementEndpoints(WmsSpecialMovementService service, WmsSpecialPolicyService policies,
	                                      WmsSpecialDirectoryService directory) {
		this.service = service;
		this.policies = policies;
		this.directory = directory;
	}

	protected abstract boolean mobile();

	@GetMapping("/context")
	public R<Context> context(@RequestParam String warehouseCode) {
		return R.ok(service.context(warehouseCode));
	}

	@GetMapping("/policy")
	public R<PolicySnapshot> policy(@RequestParam String warehouseCode) {
		return R.ok(policies.read(warehouseCode));
	}

	@GetMapping("/page")
	public R<Page> page(@RequestParam String warehouseCode, @RequestParam(defaultValue = "") String kind,
	                    @RequestParam(defaultValue = "") String state,
	                    @RequestParam(defaultValue = "") String reference,
	                    @RequestParam(defaultValue = "1") int page,
	                    @RequestParam(defaultValue = "20") int size) {
		return R.ok(service.page(warehouseCode, kind, state, reference, page, size));
	}

	@GetMapping("/orders/{id}")
	public R<View> read(@PathVariable String id, @RequestParam String warehouseCode) {
		return R.ok(service.read(warehouseCode, id));
	}

	@GetMapping("/materials")
	public R<MaterialPage> materials(@RequestParam String warehouseCode,
	                                 @RequestParam(defaultValue = "") String keyword,
	                                 @RequestParam(defaultValue = "1") int page) {
		return R.ok(directory.materials(warehouseCode, keyword, page));
	}

	@GetMapping("/sites")
	public R<List<LocationOption>> sites(@RequestParam String warehouseCode) {
		return R.ok(directory.sites(warehouseCode));
	}

	@GetMapping("/stocks")
	public R<StockPage> stocks(@RequestParam String warehouseCode, @RequestParam String orderId,
	                           @RequestParam String lineId, @RequestParam(required = false) String sourceFlowId,
	                           @RequestParam(defaultValue = "") String barcode,
	                           @RequestParam(required = false) String cursor) {
		return R.ok(directory.stocks(warehouseCode, orderId, lineId, sourceFlowId, barcode, cursor));
	}

	@PostMapping("/actions")
	@SysLog("办理WMS特殊收发")
	public R<Receipt> perform(@RequestBody Command command) {
		return R.ok(service.perform(command, mobile()));
	}

	@PostMapping("/requests/lookup")
	public R<Receipt> lookup(@RequestBody Command command) {
		return R.ok(service.lookup(command));
	}

	@PostMapping("/requests/end")
	@SysLog("安全结束WMS特殊收发原请求")
	public R<Receipt> end(@RequestBody End command) {
		return R.ok(service.end(command));
	}
}
