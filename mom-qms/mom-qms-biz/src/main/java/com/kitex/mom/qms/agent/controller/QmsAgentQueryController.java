package com.kitex.mom.qms.agent.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.qms.agent.dto.QmsBatchQualityTraceDTO;
import com.kitex.mom.qms.agent.dto.QmsBatchRecallStatusDTO;
import com.kitex.mom.qms.agent.dto.QmsInspectionOverviewDTO;
import com.kitex.mom.qms.agent.dto.QmsIncomingInspectionEvidenceDTO;
import com.kitex.mom.qms.agent.dto.QmsSupplierIncomingQualityDTO;
import com.kitex.mom.qms.agent.dto.QmsWorkOrderQualityDTO;
import com.kitex.mom.qms.agent.dto.QmsAgentNcrReferralDTO;
import com.kitex.mom.qms.agent.service.QmsBatchQualityTraceQueryService;
import com.kitex.mom.qms.agent.service.QmsBatchRecallStatusQueryService;
import com.kitex.mom.qms.agent.service.QmsInspectionOverviewQueryService;
import com.kitex.mom.qms.agent.service.QmsIncomingInspectionEvidenceService;
import com.kitex.mom.qms.agent.service.QmsSupplierIncomingQualityQueryService;
import com.kitex.mom.qms.agent.service.QmsWorkOrderQualityQueryService;
import com.kitex.mom.qms.agent.service.QmsAgentNcrReferralService;
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
    private final QmsBatchRecallStatusQueryService batchRecallStatusService;
    private final QmsWorkOrderQualityQueryService workOrderQualityService;
    private final QmsAgentNcrReferralService ncrReferralService;
    private final QmsIncomingInspectionEvidenceService incomingInspectionEvidenceService;
    private final QmsSupplierIncomingQualityQueryService supplierIncomingQualityService;

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

    /** 仅供参数异常效果复核，扩大同工单逐条取证范围；超限仍不得结案。 */
    @Inner
    @PostMapping("/internal/work-order-quality-effect")
    @PreAuthorize("@pms.hasPermission('procedureTask:show','productTask:show')")
    public R<QmsWorkOrderQualityDTO.Evidence> workOrderQualityEffect(
            @Valid @RequestBody QmsWorkOrderQualityDTO.Query query) {
        return R.ok(workOrderQualityService.queryForEffect(query));
    }

    /**
     * 质量岗位实名确认本次 Agent 调查关联的既有同源 NCR，只追加原 QMS 操作履历。
     */
    @Inner
    @PostMapping("/internal/process-deviation-ncr-referral")
    @PreAuthorize("@pms.hasPermission('agent:process-deviation:action') and "
            + "@pms.hasPermission('bad:judge') and "
            + "@pms.hasPermission('bad:show') and "
            + "(@pms.hasPermission('procedureTask:show') or @pms.hasPermission('productTask:show'))")
    public R<QmsAgentNcrReferralDTO.Receipt> acceptNcrReferral(
            @Valid @RequestBody QmsAgentNcrReferralDTO.Accept command) {
        return R.ok(ncrReferralService.accept(command));
    }

    /** 原案件关闭与 CAPA 有效性只读回执，供处置后效果评价重新核验。 */
    @Inner
    @PostMapping("/internal/process-deviation-ncr-closure")
    @PreAuthorize("@pms.hasPermission('bad:show') and "
            + "(@pms.hasPermission('procedureTask:show') or @pms.hasPermission('productTask:show'))")
    public R<QmsAgentNcrReferralDTO.ClosureReceipt> ncrClosure(
            @Valid @RequestBody QmsAgentNcrReferralDTO.Accept query) {
        return R.ok(ncrReferralService.closure(query));
    }

    @Inner
    @PostMapping("/internal/batch-quality-trace")
    @PreAuthorize("@pms.hasPermission('procedureTask:show','productTask:show','purchaseTask:show','bad:show')")
    public R<QmsBatchQualityTraceDTO.Evidence> batchQualityTrace(
            @Valid @RequestBody QmsBatchQualityTraceDTO.Query query) {
        return R.ok(batchQualityTraceService.query(query));
    }

    /**
     * 读取既有召回治理单，供批次影响Agent判断是否已进入正式QMS处置及范围是否漂移。
     */
    @Inner
    @PostMapping("/internal/batch-recall-status")
    @PreAuthorize("@pms.hasPermission('batchRecall:show')")
    public R<QmsBatchRecallStatusDTO.Evidence> batchRecallStatus(
            @Valid @RequestBody QmsBatchRecallStatusDTO.Query query) {
        return R.ok(batchRecallStatusService.query(query));
    }

    /**
     * 来料可用Agent只读取PMS冻结的精确任务主键，不按物料和批次猜测检验任务。
     */
    @Inner
    @PostMapping("/internal/incoming-inspection-evidence")
    @PreAuthorize("@pms.hasPermission('purchaseTask:show')")
    public R<QmsIncomingInspectionEvidenceDTO.Evidence> incomingInspectionEvidence(
            @Valid @RequestBody QmsIncomingInspectionEvidenceDTO.Query query) {
        return R.ok(incomingInspectionEvidenceService.query(query));
    }

    /**
     * 读取指定供应商—物料在有限时间窗内的来料检验、NCR及CAPA事实。
     */
    @Inner
    @PostMapping("/internal/supplier-incoming-quality")
    @PreAuthorize("@pms.hasPermission('purchaseTask:show','bad:show','capa:show')")
    public R<QmsSupplierIncomingQualityDTO.Evidence> supplierIncomingQuality(
            @Valid @RequestBody QmsSupplierIncomingQualityDTO.Query query) {
        return R.ok(supplierIncomingQualityService.query(query));
    }
}
