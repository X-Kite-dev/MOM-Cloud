package com.kitex.mom.wms.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.wms.dto.WmsQualityActionExecuteCommand;
import com.kitex.mom.wms.dto.WmsQualityStatusCommand;
import com.kitex.mom.wms.dto.WmsQualityStatusResult;
import com.kitex.mom.wms.dto.WmsSpecialReturnQualityDTO.*;
import com.kitex.mom.wms.service.special.WmsSpecialReturnQualityProofService;
import com.kitex.mom.wms.entity.WmsQualityStatusOperationEntity;
import com.kitex.mom.wms.service.WmsQualityStatusService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 质量正式命令、精确特殊退回来源读取及库存操作追溯入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/material/wmsLiveStock/quality")
public class WmsQualityStatusController {
	private final WmsQualityStatusService service;
	private final com.kitex.mom.wms.service.physical.WmsPhysicalService physical;
	private final com.kitex.mom.wms.service.physical.WmsPhysicalJson physicalJson;
	private final WmsSpecialReturnQualityProofService specialReturnProof;

	/**
	 * 内部QMS代理保留人员令牌；服务层另核实际工厂授权和原仓读取权限。
	 */
	@Inner
	@GetMapping("/special-return/candidates")
	public R<CandidatePage> specialReturnCandidates(CandidateQuery query) {
		return R.ok(specialReturnProof.candidates(query));
	}

	/**
	 * 仅规范化来源，正式HOLD仍须在写事务内重新核验。
	 */
	@Inner
	@PostMapping("/special-return/preflight")
	public R<Target> preflightSpecialReturn(@RequestBody Selection selection) {
		return R.ok(specialReturnProof.preflight(selection));
	}

	/**
	 * 质量状态仅接受QMS等受信业务服务命令，浏览器不能直接伪造正式放行回执。
	 */
	@Inner
	@PostMapping("/status")
	public R<WmsQualityStatusResult> apply(@RequestBody WmsQualityStatusCommand command) {
		return R.ok(service.apply(command));
	}

	/**
	 * 特殊退回命令显式版本入口；必须携冻结原件，旧服务缺此路径时不得回退通用FIFO处理。
	 */
	@Inner
	@PostMapping("/special-return/status")
	public R<WmsQualityStatusResult> applySpecialReturn(@RequestBody WmsQualityStatusCommand command) {
		if (command == null || command.getSpecialReturnBinding() == null) {
			throw new CheckedException("特殊退回质量命令必须携永久原件绑定");
		}
		return R.ok(service.apply(command));
	}

	@Operation(summary = "质量库存事务分页")
	@GetMapping("/page")
	@PreAuthorize("@pms.hasPermission('wmsQualityStatus:show')")
	public R<IPage<com.kitex.mom.wms.dto.WmsQualityPhysicalDTO.PageRow>> page(Page<?> page,
	                                                                          WmsQualityStatusOperationEntity query,
	                                                                          @RequestParam(required = false) String matBarcode) {
		return R.ok(physical.page(page, query, matBarcode));
	}

	/**
	 * 受权原操作完整回执，不依赖库存今日仍有效。
	 */
	@GetMapping("/{id}/receipt")
	@PreAuthorize("@pms.hasPermission('wmsQualityStatus:show')")
	public R<com.kitex.mom.wms.dto.WmsQualityPhysicalDTO.Detail> receipt(@PathVariable String id) {
		return R.ok(physical.receipt(id));
	}

	/**
	 * V1唯一首次实物入口；严格JSON类型后交领域事务。
	 */
	@PostMapping("/execution/apply")
	@PreAuthorize("@pms.hasPermission('wmsQualityStatus:execute')")
	public R<com.kitex.mom.wms.dto.WmsQualityPhysicalDTO.Lookup> applyPhysical(
			@RequestBody com.fasterxml.jackson.databind.JsonNode body) {
		return R.ok(physical.apply(physicalJson.command(body)));
	}

	/**
	 * 原号只读核对，故障不能冒充OPEN。
	 */
	@PostMapping("/execution/lookup")
	@PreAuthorize("@pms.hasPermission('wmsQualityStatus:show')")
	public R<com.kitex.mom.wms.dto.WmsQualityPhysicalDTO.Lookup> lookupPhysical(
			@RequestBody com.fasterxml.jackson.databind.JsonNode body) {
		return R.ok(physical.lookup(physicalJson.command(body)));
	}

	/**
	 * 实名永久结束本人原号，不做库存补偿。
	 */
	@PostMapping("/execution/cancel")
	@PreAuthorize("@pms.hasPermission('wmsQualityStatus:execute')")
	public R<com.kitex.mom.wms.dto.WmsQualityPhysicalDTO.Lookup> cancelPhysical(
			@RequestBody com.fasterxml.jackson.databind.JsonNode body) {
		return R.ok(physical.cancel(physicalJson.command(body)));
	}

	/**
	 * 普通定向重隔离专用内部路由，不兼容降级通用FIFO。
	 */
	@Inner
	@PostMapping("/physical/rehold-status")
	public R<WmsQualityStatusResult> physicalRehold(@RequestBody com.fasterxml.jackson.databind.JsonNode body) {
		return R.ok(service.applyPhysicalRehold(physicalJson.rehold(body)));
	}

	@PostMapping("/{id}/retry")
	@PreAuthorize("@pms.hasPermission('wmsQualityStatus:retry')")
	public R<WmsQualityStatusResult> retry(@PathVariable Long id) {
		return R.ok(service.retry(id));
	}

	@PostMapping("/execute")
	@PreAuthorize("@pms.hasPermission('wmsQualityStatus:execute')")
	public R<WmsQualityStatusResult> execute(@RequestBody WmsQualityActionExecuteCommand command) {
		return R.ok(service.execute(command));
	}
}
