package com.kitex.mom.qms.agent.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.qms.agent.dto.QmsBatchQualityTraceDTO;
import com.kitex.mom.qms.agent.dto.QmsInspectionOverviewDTO;
import com.kitex.mom.qms.agent.dto.QmsWorkOrderQualityDTO;
import com.kitex.mom.qms.agent.service.QmsBatchQualityTraceQueryService;
import com.kitex.mom.qms.agent.service.QmsInspectionOverviewQueryService;
import com.kitex.mom.qms.agent.service.QmsWorkOrderQualityQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * QMS 面向智能体的受控只读语义查询。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/qmsAgentQuery")
public class QmsAgentQueryController {

    private final QmsInspectionOverviewQueryService inspectionOverviewService;
    private final QmsBatchQualityTraceQueryService batchQualityTraceService;
    private final QmsWorkOrderQualityQueryService workOrderQualityService;

    @Inner
    @PostMapping("/internal/inspection-overview")
    @PreAuthorize("@pms.hasPermission('procedureTask:show','productTask:show','purchaseTask:show')")
    public R<QmsInspectionOverviewDTO.Evidence> inspectionOverview(
            @Valid @RequestBody QmsInspectionOverviewDTO.Query query) {
        return R.ok(inspectionOverviewService.query(query));
    }

    @Inner
    @PostMapping("/internal/work-order-quality")
    @PreAuthorize("@pms.hasPermission('procedureTask:show','productTask:show')")
    public R<QmsWorkOrderQualityDTO.Evidence> workOrderQuality(
            @Valid @RequestBody QmsWorkOrderQualityDTO.Query query) {
        return R.ok(workOrderQualityService.query(query));
    }

    @Inner
    @PostMapping("/internal/batch-quality-trace")
    @PreAuthorize("@pms.hasPermission('procedureTask:show','productTask:show','purchaseTask:show','bad:show')")
    public R<QmsBatchQualityTraceDTO.Evidence> batchQualityTrace(
            @Valid @RequestBody QmsBatchQualityTraceDTO.Query query) {
        return R.ok(batchQualityTraceService.query(query));
    }
}
