package com.kitex.mom.pms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.pms.dto.PmsDeliveryCommitmentCancelDTO;
import com.kitex.mom.pms.dto.PmsDeliveryCommitmentCommandDTO;
import com.kitex.mom.pms.entity.PmsDeliveryCommitmentEntity;
import com.kitex.mom.pms.service.PmsDeliveryCommitmentService;
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
@RequestMapping("/pmsDeliveryCommitment")
@Tag(description = "pmsDeliveryCommitment", name = "供应商交期承诺")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PmsDeliveryCommitmentController {
    private final PmsDeliveryCommitmentService service;

    @GetMapping("/order/{orderId}")
    @Operation(summary = "查询采购订单交期承诺版本")
    @PreAuthorize("@pms.hasPermission('pms_deliveryCommitment_view')")
    public R<List<PmsDeliveryCommitmentEntity>> listByOrder(@PathVariable Long orderId,
                                                            @RequestParam(required = false) Long orderDetailId) {
        return R.ok(service.listByOrder(orderId, orderDetailId));
    }

    @PostMapping("/replace")
    @Operation(summary = "用完整分批计划登记或变更交期承诺")
    @SysLog("登记供应商交期承诺")
    @PreAuthorize("@pms.hasPermission('pms_deliveryCommitment_maintain')")
    public R<PmsDeliveryCommitmentEntity> replace(@RequestBody PmsDeliveryCommitmentCommandDTO command) {
        return R.ok(service.replace(command));
    }

    @PostMapping("/cancel")
    @Operation(summary = "有原因取消当前交期承诺")
    @SysLog("取消供应商交期承诺")
    @PreAuthorize("@pms.hasPermission('pms_deliveryCommitment_maintain')")
    public R<Boolean> cancel(@RequestBody PmsDeliveryCommitmentCancelDTO command) {
        return R.ok(service.cancel(command));
    }
}
