package com.kitex.mom.plan.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.plan.dto.PlnMpsRemainingCommitmentDTO;
import com.kitex.mom.plan.entity.PlnMpsRemainingFulfillmentObservationEntity;
import com.kitex.mom.plan.exception.PlnMpsRemainingRejectedException;
import com.kitex.mom.plan.service.PlnMpsRemainingFulfillmentObservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 分期兑现观察与独立核验，不修改旧MPS观察或实物事实。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/plnMpsRemainingFulfillment")
public class PlnMpsRemainingFulfillmentController {
    private final PlnMpsRemainingFulfillmentObservationService service;

    /**
     * 服务事务代理成功回滚后的明确拒绝；SQL/提交/原号冲突不作未执行证明。
     */
    @ExceptionHandler(PlnMpsRemainingRejectedException.class)
    public R<PlnMpsRemainingCommitmentDTO.Rejection> rejected(PlnMpsRemainingRejectedException exception) {
        return R.ok(new PlnMpsRemainingCommitmentDTO.Rejection().setOutcome("ROLLBACK_CONFIRMED")
                .setRequestId(exception.getRequestId()).setOperationType(exception.getOperationType()).setTargetId(exception.getTargetId())
                .setPayloadSnapshot(exception.getPayloadSnapshot()).setMessage(exception.getMessage()));
    }

    @GetMapping("/preview/{commitmentId}")
    @PreAuthorize("@pms.hasPermission('plan_mps_view')")
    public R<PlnMpsRemainingCommitmentDTO.FulfillmentEvidence> preview(@PathVariable Long commitmentId) {
        return R.ok(service.preview(commitmentId));
    }

    @PostMapping("/capture")
    @PreAuthorize("@pms.hasPermission('plan_mps_submit')")
    public R<PlnMpsRemainingFulfillmentObservationEntity> capture(@RequestBody PlnMpsRemainingCommitmentDTO.FulfillmentCapture command) {
        return R.ok(service.capture(command));
    }

    @PostMapping("/review")
    @PreAuthorize("@pms.hasPermission('plan_mps_review')")
    public R<PlnMpsRemainingFulfillmentObservationEntity> review(@RequestBody PlnMpsRemainingCommitmentDTO.FulfillmentReview command) {
        return R.ok(service.review(command));
    }

    @GetMapping("/detail/{id}")
    @PreAuthorize("@pms.hasPermission('plan_mps_view')")
    public R<PlnMpsRemainingFulfillmentObservationEntity> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @GetMapping("/history/{commitmentId}")
    @PreAuthorize("@pms.hasPermission('plan_mps_view')")
    public R<Page<PlnMpsRemainingFulfillmentObservationEntity>> history(@PathVariable Long commitmentId,
                                                                        @RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.history(commitmentId, current, size));
    }

    @GetMapping("/by-request")
    @PreAuthorize("@pms.hasPermission('plan_mps_view')")
    public R<PlnMpsRemainingFulfillmentObservationEntity> byRequest(@RequestParam String requestId) {
        return R.ok(service.byRequest(requestId));
    }
}
