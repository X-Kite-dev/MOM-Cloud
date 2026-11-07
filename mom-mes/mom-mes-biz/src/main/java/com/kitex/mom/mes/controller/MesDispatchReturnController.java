package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesDispatchReturnDTO;
import com.kitex.mom.mes.entity.MesDispatchReturnRequestEntity;
import com.kitex.mom.mes.service.MesDispatchReturnService;
import com.kitex.mom.mes.service.impl.MesStartPreparationDrilldownService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 零执行配额和历史未开工派工的申请、异人审批及原命令回执入口。
 */
@Tag(description = "mesDispatchReturn", name = "受控退派")
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/dispatch-return")
public class MesDispatchReturnController {

	private final MesDispatchReturnService service;
	private final MesStartPreparationDrilldownService preparationDrilldown;

	@Operation(summary = "退派申请分页")
	@GetMapping("/page")
	@PreAuthorize("@pms.hasPermission('mesDispatchReturn:view')")
	public R<IPage<MesDispatchReturnRequestEntity>> page(Page<MesDispatchReturnRequestEntity> page,
			MesDispatchReturnRequestEntity query,
			@RequestParam(required = false) Long preparationArrangeId) {
		if (preparationArrangeId != null) {
			var arrange = preparationDrilldown.require(preparationArrangeId, query.getArrangeCode(), query.getTaskCode());
			query.setArrangeId(arrange.getId());
		}
		return R.ok(service.pageRequests(page, query));
	}

	@Operation(summary = "预览零执行派工的受控退派资格")
	@GetMapping("/preview/{arrangeId}")
	@PreAuthorize("@pms.hasPermission('mesDispatchReturn:apply')")
	public R<MesDispatchReturnDTO.Evidence> preview(@PathVariable Long arrangeId) {
		return R.ok(service.preview(arrangeId));
	}

	@Operation(summary = "查看退派申请、当前事实和永久履历")
	@GetMapping("/{requestId}")
	@PreAuthorize("@pms.hasPermission('mesDispatchReturn:view')")
	public R<MesDispatchReturnDTO.Detail> detail(@PathVariable Long requestId) {
		return R.ok(service.detail(requestId));
	}

	@Operation(summary = "提交受控退派申请")
	@SysLog("提交受控退派申请")
	@PostMapping("/submit")
	@PreAuthorize("@pms.hasPermission('mesDispatchReturn:apply')")
	public R<MesDispatchReturnRequestEntity> submit(@RequestBody MesDispatchReturnDTO.SubmitCommand command) {
		return R.ok(service.submit(command));
	}

	@Operation(summary = "异岗批准或驳回退派申请")
	@SysLog("审批受控退派申请")
	@PostMapping("/review")
	@PreAuthorize("@pms.hasPermission('mesDispatchReturn:review')")
	public R<MesDispatchReturnRequestEntity> review(@RequestBody MesDispatchReturnDTO.ReviewCommand command) {
		return R.ok(service.review(command));
	}

	@Operation(summary = "原申请人撤回待审核退派申请")
	@SysLog("撤回受控退派申请")
	@PostMapping("/withdraw")
	@PreAuthorize("@pms.hasPermission('mesDispatchReturn:apply')")
	public R<MesDispatchReturnRequestEntity> withdraw(@RequestBody MesDispatchReturnDTO.WithdrawCommand command) {
		return R.ok(service.withdraw(command));
	}

	/**
	 * 以原申请权限只读核对完整提交原文；查无记录不表示原命令未执行。
	 */
	@Operation(summary = "核对原退派提交回执")
	@PostMapping("/submit/receipt")
	@PreAuthorize("@pms.hasPermission('mesDispatchReturn:apply')")
	public R<MesDispatchReturnDTO.Receipt> submitReceipt(@RequestBody MesDispatchReturnDTO.SubmitCommand command) {
		return R.ok(service.submitReceipt(command));
	}

	/**
	 * 以原审核权限核对原命令、原审核人与永久操作，不触发重新审批。
	 */
	@Operation(summary = "核对原退派审批回执")
	@PostMapping("/review/receipt")
	@PreAuthorize("@pms.hasPermission('mesDispatchReturn:review')")
	public R<MesDispatchReturnDTO.Receipt> reviewReceipt(@RequestBody MesDispatchReturnDTO.ReviewCommand command) {
		return R.ok(service.reviewReceipt(command));
	}

	/**
	 * 以原申请权限核对原撤回命令，不把ABSENT包装为撤回成功。
	 */
	@Operation(summary = "核对原退派撤回回执")
	@PostMapping("/withdraw/receipt")
	@PreAuthorize("@pms.hasPermission('mesDispatchReturn:apply')")
	public R<MesDispatchReturnDTO.Receipt> withdrawReceipt(@RequestBody MesDispatchReturnDTO.WithdrawCommand command) {
		return R.ok(service.withdrawReceipt(command));
	}

}
