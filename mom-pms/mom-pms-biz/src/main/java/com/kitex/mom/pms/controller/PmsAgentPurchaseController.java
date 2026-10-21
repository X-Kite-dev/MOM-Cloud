package com.kitex.mom.pms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.pms.dto.*;
import com.kitex.mom.pms.entity.PmsAgentPurchaseStatusOperationEntity;
import com.kitex.mom.pms.entity.PmsAgentPurchaseStatusOutboxEntity;
import com.kitex.mom.pms.service.PmsAgentPurchaseService;
import com.kitex.mom.pms.service.PmsAgentPurchaseStatusOutboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/pmsAgentPurchase")
@Tag(name = "Agent受控采购", description = "仅供PLAN在Agent审批链路内调用")
public class PmsAgentPurchaseController {
    private final PmsAgentPurchaseService service;
    private final PmsAgentPurchaseStatusOutboxService statusOutboxService;

    @Inner
    @PostMapping("/options")
    @Operation(summary = "获取有认证、有有效价且可供货的采购候选")
    public R<PmsAgentPurchaseOptionsDTO> options(@RequestBody PmsAgentPurchaseCandidateQueryDTO query) {
        return R.ok(service.options(query));
    }

    @Inner
    @PostMapping("/execute")
    @PreAuthorize("isAuthenticated() and @pms.hasPermission('agent:approval:execute') and @pms.hasPermission('plan_planMatMrp_edit')")
    @Operation(summary = "按已审批冻结证据创建采购订单并提交PMS正式审批")
    public R<PmsAgentPurchaseReceiptDTO> execute(@RequestBody PmsAgentPurchaseCommandDTO command) {
        return R.ok(service.execute(command));
    }

    @Inner
    @GetMapping("/receipt")
    @Operation(summary = "按永久幂等号回查已创建的Agent采购回执")
    public R<PmsAgentPurchaseReceiptDTO> receipt(@RequestParam String requestId, @RequestParam Long mrpId) {
        return R.ok(service.receipt(requestId, mrpId));
    }

    @PostMapping("/status-event/retry")
    @SysLog("人工重试Agent采购驳回状态回写")
    @PreAuthorize("@pms.hasPermission('pms_pmsOrder_edit')")
    @Operation(summary = "人工重试Agent采购状态回写", description = "仅退避或死信事件允许使用新幂等号恢复")
    public R<PmsAgentPurchaseStatusOutboxEntity> retryStatusEvent(
            @RequestBody PmsAgentPurchaseStatusRetryDTO command) {
        return R.ok(statusOutboxService.retry(command == null ? null : command.getOutboxId(),
                command == null ? null : command.getRequestId(), command == null ? null : command.getReason()));
    }

    @GetMapping("/status-event/order/{orderId}")
    @PreAuthorize("@pms.hasPermission('pms_pmsOrder_view')")
    @Operation(summary = "查询Agent采购订单驳回回写状态")
    public R<PmsAgentPurchaseStatusOutboxEntity> statusEventByOrder(
            @org.springframework.web.bind.annotation.PathVariable Long orderId) {
        return R.ok(statusOutboxService.findByOrderId(orderId));
    }

    @GetMapping("/status-event/{outboxId}/operations")
    @PreAuthorize("@pms.hasPermission('pms_pmsOrder_view')")
    @Operation(summary = "查询Agent采购状态回写操作履历")
    public R<List<PmsAgentPurchaseStatusOperationEntity>> statusEventOperations(
            @org.springframework.web.bind.annotation.PathVariable Long outboxId) {
        return R.ok(statusOutboxService.operations(outboxId));
    }
}
