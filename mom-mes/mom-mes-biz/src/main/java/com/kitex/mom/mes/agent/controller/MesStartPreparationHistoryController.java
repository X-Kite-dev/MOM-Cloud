package com.kitex.mom.mes.agent.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.agent.service.MesStartPreparationHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 本人原派工核验历史与逐项对照；不采集、不调用模型、不执行正式开工。 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
@RequestMapping("/mesTask/arrange/start-preparation")
public class MesStartPreparationHistoryController {
    private final MesStartPreparationHistoryService service;

    @GetMapping("/{snapshotId}/history")
    public R<MesStartPreparationHistoryService.History> history(@PathVariable Long snapshotId,
                                                               @RequestParam(defaultValue = "1") int current) {
        return R.ok(service.history(snapshotId, current));
    }

    @GetMapping("/{snapshotId}/comparison/{baselineId}")
    public R<MesStartPreparationHistoryService.Comparison> compare(@PathVariable Long snapshotId, @PathVariable Long baselineId) {
        return R.ok(service.compare(snapshotId, baselineId));
    }
}
