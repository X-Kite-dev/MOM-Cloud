package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.service.impl.MesInspectionEventApplicationQuery;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesInspectionEventApplication")
public class MesInspectionEventApplicationController {
    private final MesInspectionEventApplicationQuery query;

    @GetMapping("/{recordId}/overview")
    @PreAuthorize("@pms.hasPermission('mesInspectionGenRecord:show')")
    public R<MesInspectionEventApplicationQuery.Overview> overview(
            @PathVariable Long recordId,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "20") int limit) {
        return R.ok(query.overview(recordId, after, limit));
    }

    @GetMapping("/{recordId}/applications/{eventId}")
    @PreAuthorize("@pms.hasPermission('mesInspectionGenRecord:show')")
    public R<MesInspectionEventApplicationQuery.ApplicationDetail> application(
            @PathVariable Long recordId, @PathVariable Long eventId) {
        return R.ok(query.applicationDetail(recordId, eventId));
    }

    @GetMapping("/{recordId}/plan-overview")
    @PreAuthorize("@pms.hasPermission('mesInspectionGenRecord:show')")
    public R<MesInspectionEventApplicationQuery.PlanPage> planOverview(
            @PathVariable Long recordId,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "20") int limit) {
        return R.ok(query.planPage(recordId, after, limit));
    }

    @GetMapping("/{recordId}/plan-deliveries")
    @PreAuthorize("@pms.hasPermission('mesInspectionGenRecord:show')")
    public R<java.util.List<com.kitex.mom.mes.entity.MesSourceQuantityPlanOutboxEntity>> plans(
            @PathVariable Long recordId,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "50") int limit) {
        return R.ok(query.planDeliveries(recordId, after, limit));
    }

    @GetMapping("/{recordId}")
    @PreAuthorize("@pms.hasPermission('mesInspectionGenRecord:show')")
    public R<MesInspectionEventApplicationQuery.View> get(
            @PathVariable Long recordId,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "50") int limit) {
        return R.ok(query.get(recordId, after, limit));
    }
}
