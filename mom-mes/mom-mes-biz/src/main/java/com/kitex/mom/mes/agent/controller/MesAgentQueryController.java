package com.kitex.mom.mes.agent.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.agent.dto.*;
import com.kitex.mom.mes.agent.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MES 面向 Agent 的受控只读语义查询。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesAgentQuery")
public class MesAgentQueryController {

    private final MesProductionProgressQueryService productionProgressService;
    private final MesProductionStatisticsQueryService productionStatisticsService;
    private final MesPlanTaskScopeQueryService planTaskScopeService;
    private final MesReportingAnalysisQueryService reportingAnalysisService;
    private final MesOperationWipQueryService operationWipService;
    private final MesBatchTraceQueryService batchTraceService;
    private final MesMaterialConsumptionQueryService materialConsumptionService;
    private final MesProcessConformanceQueryService processConformanceService;
    private final MesResourceExecutionConformanceQueryService resourceExecutionConformanceService;
    private final MesProcessTelemetryScopeQueryService processTelemetryScopeService;
    private final MesAgentObjectCandidateService objectCandidateService;

    /** 当前实名工厂的真实生产工单候选，仅用于选择原号，不启动智能体运行。 */
    @Inner
    @PostMapping("/internal/work-order-candidates")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    public R<MesAgentObjectCandidateDTO.CandidatePage> workOrderCandidates(
            @Valid @RequestBody MesAgentObjectCandidateDTO.Query query) {
        return R.ok(objectCandidateService.candidates(query));
    }

    /** 权威计划来源范围，不接受模型猜测出的订单/产品关联。 */
    @Inner
    @PostMapping("/internal/plan-task-scope")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    public R<MesPlanTaskScopeDTO.Evidence> planTaskScope(
            @Valid @RequestBody MesPlanTaskScopeDTO.Query query) {
        return R.ok(planTaskScopeService.query(query));
    }

    @Inner
    @PostMapping("/internal/production-progress")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    public R<MesProductionProgressDTO.Evidence> productionProgress(
            @Valid @RequestBody MesProductionProgressDTO.Query query) {
        return R.ok(productionProgressService.query(query));
    }

    /** 全量聚合和排名仍按MES原生产/报工来源权限授权，不授予跨工厂或自由查询。 */
    @Inner
    @PostMapping("/internal/production-statistics")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    public R<MesProductionStatisticsDTO.Evidence> productionStatistics(
            @Valid @RequestBody MesProductionStatisticsDTO.Query query) {
        return R.ok(productionStatisticsService.query(query));
    }

    @Inner
    @PostMapping("/internal/reporting-analysis")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    public R<MesReportingAnalysisDTO.Evidence> reportingAnalysis(
            @Valid @RequestBody MesReportingAnalysisDTO.Query query) {
        return R.ok(reportingAnalysisService.query(query));
    }

    @Inner
    @PostMapping("/internal/material-consumption")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    public R<MesMaterialConsumptionDTO.Evidence> materialConsumption(
            @Valid @RequestBody MesMaterialConsumptionDTO.Query query) {
        return R.ok(materialConsumptionService.query(query));
    }

    @Inner
    @PostMapping("/internal/process-conformance")
    @PreAuthorize("@pms.hasPermission('mesProduceTask:show')")
    public R<MesProcessConformanceDTO.Evidence> processConformance(
            @Valid @RequestBody MesProcessConformanceDTO.Query query) {
        return R.ok(processConformanceService.query(query));
    }

    @Inner
    @PostMapping("/internal/resource-execution-conformance")
    @PreAuthorize("@pms.hasPermission('mesProduceTask:show')")
    public R<MesResourceExecutionConformanceDTO.Evidence> resourceExecutionConformance(
            @Valid @RequestBody MesResourceExecutionConformanceDTO.Query query) {
        return R.ok(resourceExecutionConformanceService.query(query));
    }

    @Inner
    @PostMapping("/internal/process-telemetry-scope")
    @PreAuthorize("@pms.hasPermission('mesProduceTask:show')")
    public R<MesProcessTelemetryScopeDTO.Evidence> processTelemetryScope(
            @Valid @RequestBody MesProcessTelemetryScopeDTO.Query query) {
        return R.ok(processTelemetryScopeService.query(query));
    }

    @Inner
    @PostMapping("/internal/operation-wip")
    @PreAuthorize("@pms.hasPermission('mesProduceTask:show')")
    public R<MesOperationWipDTO.Evidence> operationWip(
            @Valid @RequestBody MesOperationWipDTO.Query query) {
        return R.ok(operationWipService.query(query));
    }

    @Inner
    @PostMapping("/internal/batch-trace")
    @PreAuthorize("@pms.hasPermission('mes_traceEvidence_view')")
    public R<MesBatchTraceDTO.Evidence> batchTrace(
            @Valid @RequestBody MesBatchTraceDTO.Query query) {
        return R.ok(batchTraceService.query(query));
    }
}
