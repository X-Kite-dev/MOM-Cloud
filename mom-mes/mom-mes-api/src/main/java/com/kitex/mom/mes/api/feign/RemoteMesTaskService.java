package com.kitex.mom.mes.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.common.feign.annotation.NoToken;
import com.kitex.mom.mes.Constants;
import com.kitex.mom.mes.agent.dto.*;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.vo.MesTaskReportOmsVO;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(contextId = "remoteMesTaskService", value = Constants.Name)
public interface RemoteMesTaskService {

    /**
     * 根据生产计划生成生产工单
     *
     * @param receive 计划信息
     * @return R
     */
    @PostMapping("/mesTask/add")
    R<MesTaskIssueReceiptDTO> add(@RequestBody MesTaskReceiveDTO receive);

    /**
     * 用原载荷指纹恢复已接收回执，不创建工单或更改旧订单总量。
     */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/mesTask/internal/receive-replay")
    R<MesTaskIssueReceiptDTO> recoverReceiveReceipt(
            @RequestBody List<MesTaskReceiveDTO> candidates);

    @InternalCall
    @PostMapping("/mesTask/rework")
    R<String> addRework(@RequestBody MesTaskReceiveDTO receive);

    @PostMapping("/mesTask/rework/inspection-result")
    R<Void> syncReworkInspection(@RequestBody MesReworkInspectionResultDTO result);

    /**
     * 独立服务写签名；接收端仍须验签并执行原数量门禁。
     */
    @NoToken
    @PostMapping("/mesTask/internal/rework-inspection-delivery")
    R<Void> deliverReworkInspection(@RequestBody MesReworkInspectionDeliveryDTO delivery);

    /**
     * 收妥普通工序检验原文；applied=false，不代表质量放行。
     */
    @NoToken
    @PostMapping("/mesTask/internal/inspection-event-delivery")
    R<MesInspectionEventReceiptDTO> deliverInspectionEvent(
            @RequestBody MesInspectionEventDeliveryDTO delivery);

    /**
     * 修订执行前置准入/取消/状态；独立签名，不代表质检已改判。
     */
    @NoToken
    @PostMapping("/mesTask/internal/inspection-revision-admission")
    R<MesInspectionRevisionAdmissionDTO> inspectionRevisionAdmission(@RequestBody MesInspectionRevisionDeliveryDTO command);

    @NoToken
    @PostMapping("/mesTask/internal/inspection-revision-execution")
    R<MesInspectionRevisionExecutionReceiptDTO> executeInspectionRevision(@RequestBody MesInspectionRevisionExecutionDTO command);

    @PostMapping("/mesTask/quality-disposition")
    R<Void> recordQualityDisposition(@RequestBody MesQualityDispositionDTO command);

    @GetMapping("/mesTask/internal/plan-source-mapping")
    R<MesPlanSourceMappingDTO> planSourceMapping(
            @RequestParam(value = "taskCode", required = false) String taskCode,
            @RequestParam(value = "produceTaskCode", required = false) String produceTaskCode,
            @RequestParam("factoryCode") String factoryCode);

    @PostMapping("/mesTask/internal/plan-withdraw")
    @NoToken
    R<MesPlanWithdrawalDTO.Receipt> withdrawPlan(@RequestBody MesPlanWithdrawalDTO.Command command);

    @GetMapping("/mesTask/internal/source-quantity-mapping")
    R<MesSourceQuantityMappingDTO> sourceQuantityMapping(
            @RequestParam("requestId") String requestId,
            @RequestParam("factoryCode") String factoryCode);

    /**
     * 根据生产订单号查询生产工单最后一道工序的报工记录
     *
     * @param orders 生产订单列表
     * @return R
     */
    @GetMapping("/mesTask/getByOrders")
    R<List<MesTaskReportOmsVO>> getByOrders(@RequestParam("orders") List<String> orders);

    /**
     * 生产情况
     */
    @GetMapping("/mesTask/plan/list")
    R<List<MesTaskEntity>> planMesList(@SpringQueryMap PlanMesQueryDTO planMesQueryDTO);

    /**
     * 根据生产计划单号查询生产工单
     *
     * @param planNo 生产计划单号
     * @return R
     */
    @GetMapping("/mesTask/omsLife")
    R<List<MesTaskEntity>> omsLife(@RequestParam("planNo") String planNo);

    /**
     * 更新生产工单（用于重排后同步计划时间）
     *
     * @param mesTask 工单实体（planNo + planStartDate + planEndDate）
     * @return R
     */
    @PostMapping("/mesTask/update")
    R<Void> update(@RequestBody MesTaskEntity mesTask);

    @InternalCall
    @GetMapping("/mesApsSchedule/internal/operation-states")
    R<MesApsLocalScheduleDTO.StateBundle> apsOperationStates(
            @RequestParam("planNo") String planNo, @RequestParam("factoryCode") String factoryCode);

    @InternalCall
    @PostMapping("/mesApsSchedule/internal/apply")
    R<MesApsLocalScheduleDTO.ApplyReceipt> applyApsLocalSchedule(
            @RequestBody MesApsLocalScheduleDTO.ApplyCommand command);

    /**
     * 必须转发当前实名令牌，读取原应用账及当前精确计划，不能匿名核销。
     */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/mesApsPublicationProbe/internal/probe")
    R<MesApsPublicationProbeDTO.Reply> probeApsPublication(
            @RequestBody MesApsPublicationProbeDTO.Query query);

    @InternalCall(userTokenRequired = true)
    @PostMapping("/mesApsPublicationProbe/internal/catalog")
    R<java.util.List<MesApsPublicationProbeDTO.ReceiptRef>> apsPublicationCatalog(
            @RequestBody MesApsPublicationProbeDTO.Scope scope);

    /** 实名只读核对精确原应用账，不重新发送局部排程。 */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/mesApsPublicationProbe/internal/audit")
    R<MesApsPublicationProbeDTO.AuditReply> auditApsPublication(
            @RequestBody MesApsPublicationProbeDTO.AuditQuery query);

    @InternalCall
    @PostMapping("/mesDurationEvidence/internal/query")
    R<MesDurationEvidenceDTO.Bundle> durationEvidence(
            @RequestBody MesDurationEvidenceDTO.Query query);

    /**
     * 当前人员在实际工厂范围内查询生产进度权威证据。
     */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/mesAgentQuery/internal/production-progress")
    R<MesProductionProgressDTO.Evidence> productionProgress(
            @RequestBody MesProductionProgressDTO.Query query);

    /**
     * 当前人员在实际工厂范围内冻结生产计划对应的直接及APS合批工单范围。
     */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/mesAgentQuery/internal/plan-task-scope")
    R<MesPlanTaskScopeDTO.Evidence> planTaskScope(@RequestBody MesPlanTaskScopeDTO.Query query);

    /**
     * 当前人员在实际工厂范围内查询报工与质量数量权威证据。
     */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/mesAgentQuery/internal/reporting-analysis")
    R<MesReportingAnalysisDTO.Evidence> reportingAnalysis(
            @RequestBody MesReportingAnalysisDTO.Query query);

    /**
     * 当前人员在实际工厂范围内查询指定工单有效报工对应的实际物料消耗。
     */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/mesAgentQuery/internal/material-consumption")
    R<MesMaterialConsumptionDTO.Evidence> materialConsumption(
            @RequestBody MesMaterialConsumptionDTO.Query query);

    /**
     * 当前人员在实际工厂范围内核对指定工单冻结工艺与工序任务。
     */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/mesAgentQuery/internal/process-conformance")
    R<MesProcessConformanceDTO.Evidence> processConformance(
            @RequestBody MesProcessConformanceDTO.Query query);

    /**
     * 当前人员在实际工厂范围内核对指定工单计划资源与现场执行资源。
     */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/mesAgentQuery/internal/resource-execution-conformance")
    R<MesResourceExecutionConformanceDTO.Evidence> resourceExecutionConformance(
            @RequestBody MesResourceExecutionConformanceDTO.Query query);

    /**
     * 为 IoT 参数取证返回指定工单的实际设备连续生产时间窗。
     */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/mesAgentQuery/internal/process-telemetry-scope")
    R<MesProcessTelemetryScopeDTO.Evidence> processTelemetryScope(
            @RequestBody MesProcessTelemetryScopeDTO.Query query);

    /**
     * IoT 告警或生产任务对应的唯一实际节点和冻结参数范围。
     */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/mesAgentQuery/internal/process-deviation-scope")
    R<MesProcessDeviationScopeDTO.Evidence> processDeviationScope(
            @RequestBody MesProcessDeviationScopeDTO.Query query);

    /**
     * 当前人员在实际工厂范围内查询工序在制与停滞风险权威证据。
     */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/mesAgentQuery/internal/operation-wip")
    R<MesOperationWipDTO.Evidence> operationWip(@RequestBody MesOperationWipDTO.Query query);

    /**
     * 原料物料批次到实际报工和包装标识的受控正向追溯。
     */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/mesAgentQuery/internal/batch-trace")
    R<MesBatchTraceDTO.Evidence> batchTrace(@RequestBody MesBatchTraceDTO.Query query);
}
