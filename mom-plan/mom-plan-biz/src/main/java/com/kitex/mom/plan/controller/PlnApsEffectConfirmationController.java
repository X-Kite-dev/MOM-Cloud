package com.kitex.mom.plan.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.plan.service.PlnApsEffectConfirmationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 原效果偏差行动确认查询，不创建或推进APS案件。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/plnApsCase/effect-review")
public class PlnApsEffectConfirmationController {
    private final PlnApsEffectConfirmationQueryService service;

    @GetMapping("/{reviewId}/actions/{actionId}/confirmation")
    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingEffect_view') && @pms.hasPermission('plan_plnSchedulingRule_view')")
    public R<PlnApsEffectConfirmationQueryService.Confirmation> read(@PathVariable Long reviewId,
            @PathVariable Long actionId, @RequestParam String expectedEffectFingerprint) {
        return R.ok(service.read(reviewId, actionId, expectedEffectFingerprint));
    }
}
