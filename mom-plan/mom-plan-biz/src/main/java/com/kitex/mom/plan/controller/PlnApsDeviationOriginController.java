package com.kitex.mom.plan.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.plan.service.PlnApsDeviationOriginQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 原执行偏差局部重排依据，沿PLAN及原资源/效果查看权，不提供写操作。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/plnApsCase")
public class PlnApsDeviationOriginController {
    private final PlnApsDeviationOriginQueryService service;

    @GetMapping("/{caseId}/replan/candidates/{candidateId}/origin")
    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view') && @pms.hasPermission('plan_resourceReplan_view') && @pms.hasPermission('plan_plnSchedulingEffect_view')")
    public R<PlnApsDeviationOriginQueryService.Origin> read(@PathVariable Long caseId, @PathVariable Long candidateId,
                                                         @RequestParam(defaultValue = "1") int current) {
        return R.ok(service.read(caseId, candidateId, current));
    }
}
