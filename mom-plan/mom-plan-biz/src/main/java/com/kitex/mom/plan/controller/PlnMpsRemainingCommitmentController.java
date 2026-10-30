package com.kitex.mom.plan.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.plan.dto.PlnMpsRemainingCommitmentDTO;
import com.kitex.mom.plan.entity.PlnMpsRemainingCommitmentEntity;
import com.kitex.mom.plan.entity.PlnMpsRemainingCommitmentOperationEntity;
import com.kitex.mom.plan.exception.PlnMpsRemainingRejectedException;
import com.kitex.mom.plan.service.PlnMpsRemainingCommitmentService;
import com.kitex.mom.plan.service.impl.PlnMpsRemainingCommitmentEvidenceReader;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 剩余承诺领域入口；基线只读不是正式编制/发布授权，不提供通用CRUD。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/plnMpsRemainingCommitment")
public class PlnMpsRemainingCommitmentController {
    private final PlnMpsRemainingCommitmentEvidenceReader evidenceReader;
    private final PlnMpsRemainingCommitmentService service;

    /**
     * 服务事务代理成功回滚后才收到本类型；提交/SQL/原号冲突保持未知。
     */
    @ExceptionHandler(PlnMpsRemainingRejectedException.class)
    public R<PlnMpsRemainingCommitmentDTO.Rejection> rejected(PlnMpsRemainingRejectedException exception) {
        return R.ok(new PlnMpsRemainingCommitmentDTO.Rejection().setOutcome("ROLLBACK_CONFIRMED")
                .setRequestId(exception.getRequestId()).setOperationType(exception.getOperationType()).setTargetId(exception.getTargetId())
                .setPayloadSnapshot(exception.getPayloadSnapshot()).setMessage(exception.getMessage()));
    }

    @GetMapping("/baseline/{observationId}/{lineId}")
    @PreAuthorize("@pms.hasPermission('plan_mps_view')")
    @Operation(summary = "独立确认观察的剩余数量基线与当前推进核清")
    public R<PlnMpsRemainingCommitmentDTO.BaselinePreview> baseline(
            @PathVariable Long observationId, @PathVariable Long lineId) {
        return R.ok(evidenceReader.preview(observationId, lineId));
    }

    @PostMapping("/preview")
    @PreAuthorize("@pms.hasPermission('plan_mps_view')")
    @Operation(summary = "剩余分期、原交期与最新粗能力完整预览")
    public R<PlnMpsRemainingCommitmentDTO.Preview> preview(@RequestBody PlnMpsRemainingCommitmentDTO.PreviewCommand command) {
        return R.ok(service.preview(command));
    }

    @PostMapping("/submit")
    @PreAuthorize("@pms.hasPermission('plan_mps_submit')")
    @Operation(summary = "冻结不可变剩余责任待审版本，不修改原生产事实")
    public R<PlnMpsRemainingCommitmentDTO.Detail> submit(@RequestBody PlnMpsRemainingCommitmentDTO.Submit command) {
        return R.ok(service.submit(command));
    }

    @GetMapping("/detail/{id}")
    @PreAuthorize("@pms.hasPermission('plan_mps_view')")
    public R<PlnMpsRemainingCommitmentDTO.Detail> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @GetMapping("/history/{lineId}")
    @PreAuthorize("@pms.hasPermission('plan_mps_view')")
    public R<Page<PlnMpsRemainingCommitmentEntity>> history(@PathVariable Long lineId,
                                                            @RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.history(lineId, current, size));
    }

    @GetMapping("/by-request")
    @PreAuthorize("@pms.hasPermission('plan_mps_view')")
    @Operation(summary = "永久命令原回执；未找到不能作为未执行证明")
    public R<PlnMpsRemainingCommitmentOperationEntity> byRequest(@RequestParam String requestId) {
        return R.ok(service.byRequest(requestId));
    }

    @PostMapping("/cancel")
    @PreAuthorize("@pms.hasPermission('plan_mps_submit')")
    public R<PlnMpsRemainingCommitmentDTO.Detail> cancel(@RequestBody PlnMpsRemainingCommitmentDTO.Cancel command) {
        return R.ok(service.cancel(command));
    }

    @PostMapping("/review")
    @PreAuthorize("@pms.hasPermission('plan_mps_review')")
    @Operation(summary = "独立审核及最新RCCP重核，精确前版原子替代")
    public R<PlnMpsRemainingCommitmentDTO.Detail> review(@RequestBody PlnMpsRemainingCommitmentDTO.Review command) {
        return R.ok(service.review(command));
    }
}
