package com.kitex.mom.pms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.pms.dto.PmsOmsSupplyWithdrawalDTO;
import com.kitex.mom.pms.dto.PmsOrderChangeDTO;
import com.kitex.mom.pms.entity.PmsOrderChangeRequestEntity;
import com.kitex.mom.pms.service.PmsOrderChangeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/pmsOrderChange")
@Tag(description = "pmsOrderChange", name = "采购订单受控变更")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PmsOrderChangeController {
    private final PmsOrderChangeService service;

    @GetMapping("/cancel-preview/{orderId}")
    @Operation(summary = "预览采购订单未到货剩余量取消及下游阻断")
    @PreAuthorize("@pms.hasPermission('pms_orderChange_view')")
    public R<PmsOrderChangeDTO.Preview> preview(@PathVariable Long orderId) {
        return R.ok(service.previewCancelRemaining(orderId));
    }

    @PostMapping("/line-cancel-preview")
    @Operation(summary = "预览指定采购订单行未到货供给撤回及合同、收料阻断")
    @PreAuthorize("@pms.hasPermission('pms_orderChange_view')")
    public R<PmsOrderChangeDTO.Preview> previewLines(
            @RequestBody PmsOrderChangeDTO.LineCancelPreviewCommand command) {
        return R.ok(service.previewCancelLines(command));
    }

    @PostMapping("/amend-preview")
    @Operation(summary = "预览已审批采购订单数量、交期、价格或供应商变更")
    @PreAuthorize("@pms.hasPermission('pms_orderChange_view')")
    public R<PmsOrderChangeDTO.Preview> previewAmendment(@RequestBody PmsOrderChangeDTO.AmendPreviewCommand command) {
        return R.ok(service.previewAmendment(command));
    }

    @PostMapping("/cancel-submit")
    @Operation(summary = "提交取消采购订单全部未到货剩余量申请")
    @SysLog("提交采购订单剩余量取消申请")
    @PreAuthorize("@pms.hasPermission('pms_orderChange_submit')")
    public R<PmsOrderChangeDTO.Detail> submit(@RequestBody PmsOrderChangeDTO.SubmitCommand command) {
        return R.ok(service.submitCancelRemaining(command));
    }

    @PostMapping("/line-cancel-submit")
    @Operation(summary = "提交指定采购订单行未到货供给撤回申请")
    @SysLog("提交采购订单行供给撤回申请")
    @PreAuthorize("@pms.hasPermission('pms_orderChange_submit')")
    public R<PmsOrderChangeDTO.Detail> submitLines(
            @RequestBody PmsOrderChangeDTO.LineCancelSubmitCommand command) {
        return R.ok(service.submitCancelLines(command));
    }

    @PostMapping("/amend-submit")
    @Operation(summary = "提交已审批采购订单通用变更申请")
    @SysLog("提交采购订单通用变更申请")
    @PreAuthorize("@pms.hasPermission('pms_orderChange_submit')")
    public R<PmsOrderChangeDTO.Detail> submitAmendment(@RequestBody PmsOrderChangeDTO.AmendSubmitCommand command) {
        return R.ok(service.submitAmendment(command));
    }

    @PostMapping("/review")
    @Operation(summary = "异岗复核并原子执行采购订单受控变更")
    @SysLog("复核采购订单受控变更申请")
    @PreAuthorize("@pms.hasPermission('pms_orderChange_review')")
    public R<PmsOrderChangeDTO.Detail> review(@RequestBody PmsOrderChangeDTO.ReviewCommand command) {
        return R.ok(service.review(command));
    }

    @GetMapping("/{requestId}")
    @Operation(summary = "查询采购订单变更申请、行证据及操作账")
    @PreAuthorize("@pms.hasPermission('pms_orderChange_view')")
    public R<PmsOrderChangeDTO.Detail> detail(@PathVariable Long requestId) {
        return R.ok(service.detail(requestId));
    }

    @GetMapping("/order/{orderId}")
    @Operation(summary = "查询采购订单全部变更申请")
    @PreAuthorize("@pms.hasPermission('pms_orderChange_view')")
    public R<List<PmsOrderChangeRequestEntity>> listByOrder(@PathVariable Long orderId) {
        return R.ok(service.listByOrder(orderId));
    }

    @PostMapping("/internal/oms-line-withdrawal-preview")
    @Inner
    @Operation(summary = "OMS级联预览精确采购订单行撤回事实")
    public R<PmsOrderChangeDTO.Preview> previewOmsLineWithdrawal(
            @RequestBody PmsOmsSupplyWithdrawalDTO.PreviewCommand command) {
        return R.ok(service.previewOmsLineWithdrawal(command));
    }

    @PostMapping("/internal/oms-line-withdrawal-submit")
    @Inner
    @Operation(summary = "OMS级联幂等创建采购订单行撤回申请")
    public R<PmsOmsSupplyWithdrawalDTO.Receipt> submitOmsLineWithdrawal(
            @RequestBody PmsOmsSupplyWithdrawalDTO.SubmitCommand command) {
        return R.ok(service.submitOmsLineWithdrawal(command));
    }

    @GetMapping("/internal/oms-line-withdrawal-receipt")
    @Inner
    @Operation(summary = "OMS按永久请求号查询采购撤回审批回执")
    public R<PmsOmsSupplyWithdrawalDTO.Receipt> getOmsLineWithdrawalReceipt(@RequestParam String requestId) {
        return R.ok(service.getOmsLineWithdrawalReceipt(requestId));
    }
}
