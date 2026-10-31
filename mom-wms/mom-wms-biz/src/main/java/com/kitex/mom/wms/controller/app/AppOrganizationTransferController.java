package com.kitex.mom.wms.controller.app;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsOrganizationTransferDTO.Command;
import com.kitex.mom.wms.dto.WmsOrganizationTransferDTO.Detail;
import com.kitex.mom.wms.dto.WmsOrganizationTransferDTO.Effect;
import com.kitex.mom.wms.dto.WmsOrganizationTransferDTO.History;
import com.kitex.mom.wms.dto.WmsOrganizationTransferDTO.Page;
import com.kitex.mom.wms.dto.WmsOrganizationTransferDTO.PolicyCommand;
import com.kitex.mom.wms.dto.WmsOrganizationTransferDTO.Receipt;
import com.kitex.mom.wms.dto.WmsOrganizationTransferDTO.StockPage;
import com.kitex.mom.wms.dto.app.WmsMobileOrganizationTransferDTO.MobileContext;
import com.kitex.mom.wms.entity.organization.WmsOrganizationTransferPolicy;
import com.kitex.mom.wms.service.organization.WmsOrganizationTransferAccess;
import com.kitex.mom.wms.service.organization.WmsOrganizationTransferPolicyService;
import com.kitex.mom.wms.service.organization.WmsOrganizationTransferService;
import com.kitex.mom.wms.service.organization.WmsOrganizationTransferStockService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.kitex.mom.wms.service.organization.WmsOrganizationTransferRules.require;

/**
 * 组织调拨独立手机通道；写入由服务端常量选择移动准入，原号恢复沿原授权合同。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/organizationTransfer")
public class AppOrganizationTransferController {
	private final WmsOrganizationTransferService service;
	private final WmsOrganizationTransferPolicyService policies;
	private final WmsOrganizationTransferAccess access;
	private final WmsOrganizationTransferStockService stocks;

	@GetMapping("/context")
	public R<MobileContext> context(@RequestParam String warehouseCode) {
		return R.ok(service.mobileContext(warehouseCode));
	}

	@GetMapping("/policy")
	public R<WmsOrganizationTransferPolicy> policy(@RequestParam String warehouseCode) {
		return R.ok(policies.current(warehouseCode));
	}

	@PutMapping("/policy")
	@SysLog("手机端维护库存组织")
	public R<WmsOrganizationTransferPolicy> savePolicy(@RequestBody PolicyCommand command) {
		return R.ok(policies.saveMobile(command));
	}

	@GetMapping("/directory")
	public R<List<WmsOrganizationTransferPolicy>> directory(@RequestParam String warehouseCode) {
		return R.ok(service.directory(warehouseCode));
	}

	@GetMapping("/stocks")
	public R<StockPage> stocks(@RequestParam String warehouseCode,
	                           @RequestParam(required = false) String materialCode,
	                           @RequestParam(required = false) String barcode,
	                           @RequestParam(required = false) Long beforeStockId) {
		var scope = access.scope(warehouseCode, false);
		policies.active(scope);
		return R.ok(stocks.candidates(scope, materialCode, barcode, beforeStockId));
	}

	@GetMapping("/page")
	public R<Page> page(@RequestParam String warehouseCode, @RequestParam(defaultValue = "") String state,
	                    @RequestParam(defaultValue = "") String billNo,
	                    @RequestParam(defaultValue = "1") int current, @RequestParam(defaultValue = "20") int size) {
		return R.ok(service.page(warehouseCode, state, billNo, current, size));
	}

	@GetMapping("/{id}")
	public R<Detail> detail(@PathVariable Long id, @RequestParam String warehouseCode) {
		return R.ok(service.mobileDetail(warehouseCode, id));
	}

	@GetMapping("/{id}/history")
	public R<List<History>> history(@PathVariable Long id, @RequestParam String warehouseCode,
	                                @RequestParam(defaultValue = "1") int page) {
		return R.ok(service.history(warehouseCode, id, page));
	}

	@GetMapping("/{id}/effects")
	public R<List<Effect>> effects(@PathVariable Long id, @RequestParam String warehouseCode,
	                               @RequestParam(defaultValue = "1") int page) {
		return R.ok(service.effects(warehouseCode, id, page));
	}

	@PostMapping("/actions")
	@SysLog("手机端办理组织调拨")
	public R<Receipt> execute(@RequestBody Command command) {
		return R.ok(service.executeMobile(command));
	}

	@GetMapping("/operations/{requestId}")
	public R<Receipt> lookup(@PathVariable String requestId, @RequestParam String warehouseCode) {
		return R.ok(service.lookup(warehouseCode, requestId));
	}

	public record Abandon(Command command, String reason) {
	}

	@PostMapping("/abandon")
	@SysLog("取消组织调拨未执行请求")
	public R<Receipt> abandon(@RequestBody Abandon command) {
		require(command != null, "请选择待核对的原请求");
		return R.ok(service.abandon(command.command(), command.reason()));
	}
}
