package com.kitex.mom.plan.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.plan.dto.*;
import com.kitex.mom.plan.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能 APS 案件内部只读接口，供 MomAI 获取受控上下文。
 */
@Inner
@RestController
@RequiredArgsConstructor
@RequestMapping("/plnApsCase/internal")
@Tag(name = "智能APS案件内部只读接口")
public class PlnApsCaseInternalController {

    private final PlnApsCaseService service;
    private final PlnApsResourceSnapshotService resourceSnapshotService;
    private final PlnApsDecisionService decisionService;
    private final PlnApsReplanAdapter replanAdapter;
    private final PlnSchedulingEffectReviewService effectReviewService;

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new com.kitex.common.core.exception.CheckedException(message);
        }
    }

    @GetMapping("/{caseId}")
    @Operation(summary = "按租户和factory-code读取案件上下文")
    public R<PlnApsCaseDTO> detail(@PathVariable Long caseId) {
        return R.ok(service.detail(caseId));
    }

    @GetMapping("/{caseId}/resources/{snapshotId}")
    @Operation(summary = "读取案件当前资源快照和预检问题")
    public R<PlnApsResourceDTO.Snapshot> resources(@PathVariable Long caseId,
                                                   @PathVariable Long snapshotId) {
        PlnApsCaseDTO apsCase = service.detail(caseId);
        require(snapshotId.toString().equals(apsCase.getResourceSnapshotId()),
                "资源快照不属于当前APS案件或已经失效");
        return R.ok(resourceSnapshotService.detail(caseId, snapshotId));
    }

    @GetMapping("/{caseId}/decision")
    @Operation(summary = "读取案件当前候选比较证据")
    public R<PlnApsDecisionDTO.Comparison> decision(@PathVariable Long caseId) {
        return R.ok(decisionService.compare(caseId));
    }

    @GetMapping("/{caseId}/replan")
    @Operation(summary = "读取案件关联的专业重排事实投影")
    public R<PlnApsReplanDTO.Projection> replan(@PathVariable Long caseId) {
        return R.ok(replanAdapter.projection(caseId));
    }

    @GetMapping("/{caseId}/effect/{reviewId}")
    @Operation(summary = "读取案件当前排程效果回评证据")
    public R<PlnSchedulingEffectReviewDTO.Detail> effect(@PathVariable Long caseId,
                                                         @PathVariable Long reviewId) {
        PlnApsCaseDTO apsCase = service.detail(caseId);
        require(reviewId.toString().equals(apsCase.getEffectReviewId()),
                "效果回评不属于当前APS案件或已经失效");
        return R.ok(effectReviewService.detail(reviewId));
    }
}
