package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsMesIssueCommand;
import com.kitex.mom.wms.dto.WmsMesIssueCancelCommand;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.service.impl.WmsMesIssueOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsMesIssueOutbox")
public class WmsMesIssueOutboxController {
	private final WmsMesIssueOutboxService service;

	@GetMapping("/page")
	@PreAuthorize("@pms.hasPermission('wms_mesIssue_view')")
	public R<?> page(@RequestParam String factoryCode, @RequestParam(required = false) String billNo,
	                 @RequestParam(required = false) String status, @RequestParam(defaultValue = "1") long current,
	                 @RequestParam(defaultValue = "20") long size) {
		return R.ok(service.page(factoryCode, billNo, status, current, size));
	}

	@GetMapping("/{id}/operations")
	@PreAuthorize("@pms.hasPermission('wms_mesIssue_view')")
	public R<?> operations(@PathVariable Long id) {
		return R.ok(service.operations(id));
	}

	@GetMapping("/{id}")
	@PreAuthorize("@pms.hasPermission('wms_mesIssue_view')")
	public R<?> detail(@PathVariable Long id) {
		return R.ok(service.detail(id));
	}

	@GetMapping("/{id}/evidence")
	@PreAuthorize("@pms.hasPermission('wms_mesIssue_view')")
	public R<?> evidence(@PathVariable Long id) {
		return R.ok(service.evidence(id));
	}

	@GetMapping("/operationContext")
	@PreAuthorize("@pms.hasPermission('wms_mesIssue_view')")
	public R<?> operationContext() {
		return R.ok(service.operationContext());
	}

	@PostMapping("/operations/lookup")
	@PreAuthorize("@pms.hasPermission('wms_mesIssue_view')")
	public R<?> lookupOperation(@RequestBody WmsMesIssueCommand command) {
		return R.ok(service.lookupOperation(command));
	}

	@PostMapping("/retry/prepare")
	@PreAuthorize("@pms.hasPermission('wms_mesIssue_view') and @pms.hasPermission('wms_mesIssue_retry')")
	public R<?> prepareRetry(@RequestBody WmsMesIssueCommand command) {
		return R.ok(service.prepareOperation(command, false));
	}

	@PostMapping("/backfill/prepare")
	@PreAuthorize("@pms.hasPermission('wms_mesIssue_view') and @pms.hasPermission('wms_mesIssue_backfill')")
	public R<?> prepareBackfill(@RequestBody WmsMesIssueCommand command) {
		return R.ok(service.prepareOperation(command, true));
	}

	@PostMapping("/retry/cancel")
	@SysLog("结束未执行发料重试请求")
	@PreAuthorize("@pms.hasPermission('wms_mesIssue_view') and @pms.hasPermission('wms_mesIssue_retry')")
	public R<?> cancelRetry(@RequestBody WmsMesIssueCancelCommand command) {
		validateCancellationRoute(command, false);
		return R.ok(service.cancelOperation(command.getOriginal(), command.getReason()));
	}

	@PostMapping("/backfill/cancel")
	@SysLog("结束未执行历史补建请求")
	@PreAuthorize("@pms.hasPermission('wms_mesIssue_view') and @pms.hasPermission('wms_mesIssue_backfill')")
	public R<?> cancelBackfill(@RequestBody WmsMesIssueCancelCommand command) {
		validateCancellationRoute(command, true);
		return R.ok(service.cancelOperation(command.getOriginal(), command.getReason()));
	}

	private void validateCancellationRoute(WmsMesIssueCancelCommand command, boolean backfill) {
		if (command == null || command.getOriginal() == null ||
				backfill != (command.getOriginal().getWmsBillId() != null)) {
			throw new CheckedException("结束请求的业务类型与入口不一致");
		}
	}

	@PostMapping("/retry")
	@SysLog("人工续跑WMS发料投递")
	@PreAuthorize("@pms.hasPermission('wms_mesIssue_retry')")
	public R<?> retry(@RequestBody WmsMesIssueCommand command) {
		return R.ok(service.retry(command));
	}

	@PostMapping("/backfill")
	@SysLog("历史已完成发料补建MES投递")
	@PreAuthorize("@pms.hasPermission('wms_mesIssue_backfill')")
	public R<?> backfill(@RequestBody WmsMesIssueCommand command) {
		return R.ok(service.backfill(command));
	}
}
