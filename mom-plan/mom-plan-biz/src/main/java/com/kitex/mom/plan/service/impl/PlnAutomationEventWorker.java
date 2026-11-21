package com.kitex.mom.plan.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.plan.dto.*;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 单事件独立事务执行器；失败时业务副作用和PROCESSING状态一并回滚。
 */
@Service
@RequiredArgsConstructor
public class PlnAutomationEventWorker {
    private final PlnAutomationEventMapper eventMapper;
    private final PlnAutomationEventOperationMapper operationMapper;
    private final PlnIssueCallbackReceiptMapper callbackReceiptMapper;
    private final PlnIssueMapper issueMapper;
    private final PlanMatMrpMapper mrpMapper;
    private final PlnSchedulingOperationMapper schedulingOperationMapper;
    private final PlnSchedulingEngineService schedulingEngineService;
    private final PlnQualityLocalReplanService qualityLocalReplanService;
    private final PlnResourceLocalReplanService resourceLocalReplanService;
    private final PlnDeviceRecoveryEventHandler deviceRecoveryEventHandler;
    private final PlnRepairResumeEventHandler repairResumeEventHandler;
    private final PlnCompletionReopenEventHandler completionReopenEventHandler;
    private final PlnShortageLocalReplanService shortageLocalReplanService;
    private final PlnInsertionLocalReplanService insertionLocalReplanService;
    private final ObjectMapper objectMapper;

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Boolean processOne(Long eventInboxId) {
        PlnAutomationEventEntity event = eventMapper.lockById(eventInboxId);
        if (event == null
                || !List.of("RECEIVED", "RETRY_WAIT").contains(event.getStatus())
                || event.getNextAttemptTime() == null
                || event.getNextAttemptTime().isAfter(LocalDateTime.now())) return false;
        require(
                Objects.equals(event.getTenantId(), TenantContextHolder.getTenantId())
                        && event.getTenantId() != null
                        && event.getTenantId() > 0
                        && Objects.equals(
                        event.getFactoryCode(), FactoryContextHolder.getFactoryCode())
                        && event.getFactoryCode() != null
                        && !event.getFactoryCode().isBlank()
                        && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()),
                "制造事件执行必须进入实际租户工厂");
        String before = event.getStatus();
        int attempt = Objects.requireNonNullElse(event.getAttemptCount(), 0) + 1;
        event.setStatus("PROCESSING")
                .setAttemptCount(attempt)
                .setLastStartedTime(LocalDateTime.now());
        require(eventMapper.updateById(event) == 1, "事件执行占用失败");
        operation(
                event,
                "PROCESS:" + event.getEventNo() + ':' + attempt,
                "PROCESS_START",
                before,
                "PROCESSING",
                attempt,
                "处理器=" + event.getHandlerCode());

        HandlerResult result = dispatch(event, parse(event.getPayloadJson()));
        event.setStatus("SUCCEEDED")
                .setCompletedTime(LocalDateTime.now())
                .setNextAttemptTime(null)
                .setLastErrorCode(null)
                .setLastErrorMessage(null)
                .setResultSummary(result.summary)
                .setTargetType(result.targetType)
                .setTargetId(result.targetId)
                .setTargetNo(result.targetNo);
        require(
                eventMapper.update(
                        null,
                        new LambdaUpdateWrapper<PlnAutomationEventEntity>()
                                .eq(
                                        PlnAutomationEventEntity::getTenantId,
                                        event.getTenantId())
                                .eq(
                                        PlnAutomationEventEntity::getFactoryCode,
                                        event.getFactoryCode())
                                .eq(PlnAutomationEventEntity::getId, event.getId())
                                .eq(PlnAutomationEventEntity::getStatus, "PROCESSING")
                                .set(PlnAutomationEventEntity::getStatus, "SUCCEEDED")
                                .set(
                                        PlnAutomationEventEntity::getCompletedTime,
                                        event.getCompletedTime())
                                .set(PlnAutomationEventEntity::getNextAttemptTime, null)
                                .set(PlnAutomationEventEntity::getLastErrorCode, null)
                                .set(PlnAutomationEventEntity::getLastErrorMessage, null)
                                .set(
                                        PlnAutomationEventEntity::getResultSummary,
                                        result.summary)
                                .set(
                                        PlnAutomationEventEntity::getTargetType,
                                        result.targetType)
                                .set(PlnAutomationEventEntity::getTargetId, result.targetId)
                                .set(PlnAutomationEventEntity::getTargetNo, result.targetNo)
                                .set(
                                        PlnAutomationEventEntity::getUpdateTime,
                                        LocalDateTime.now()))
                        == 1,
                "事件成功状态保存失败");
        operation(
                event,
                "SUCCESS:" + event.getEventNo() + ':' + attempt,
                "PROCESS_SUCCESS",
                "PROCESSING",
                "SUCCEEDED",
                attempt,
                result.operationContent);
        return true;
    }

    private HandlerResult dispatch(PlnAutomationEventEntity event, JsonNode payload) {
        return switch (event.getHandlerCode()) {
            case "APS_SIMULATE" -> simulate(event, payload);
            case "QUALITY_EVALUATE" -> evaluateQuality(payload);
            case "QUALITY_SOURCE_RECONCILE" -> reconcileQualitySource(event, payload);
            case "RESOURCE_LOCAL_REPLAN_ASSESS" -> assessResource(event, payload);
            case "RESOURCE_RECOVERY_REVIEW" -> reviewDeviceRecovery(event, payload);
            case "MES_REPAIR_RESUME_OBSERVE" -> observeRepairResume(event, payload);
            case "MES_COMPLETION_REOPEN_APPLY" -> applyCompletionReopen(event, payload);
            case "SHORTAGE_LOCAL_REPLAN_PROPOSE" -> proposeShortage(event, payload);
            case "INSERTION_LOCAL_REPLAN_ASSESS" -> assessInsertion(event, payload);
            default -> throw new NonRetryableEventException("事件处理器未注册：" + event.getHandlerCode());
        };
    }

    private HandlerResult simulate(PlnAutomationEventEntity event, JsonNode payload) {
        String cycleType = requiredText(payload, "cycleType").toUpperCase();
        if (!List.of("DAY", "WEEK", "MONTH").contains(cycleType))
            throw new NonRetryableEventException("排产周期只允许DAY/WEEK/MONTH");
        LocalDate scheduleDate = parseDate(requiredText(payload, "scheduleDate"), "scheduleDate");
        PlnSchedulingScenarioRunDTO command = new PlnSchedulingScenarioRunDTO();
        command.setRequestId("EVT-SCN-" + event.getId());
        command.setFactoryCode(event.getFactoryCode());
        command.setTriggerType("EVENT");
        command.setCycleType(cycleType);
        command.setScheduleDate(scheduleDate);
        command.setPlanNum(optionalInt(payload, "planNum"));
        command.setTargetIds(longList(payload.get("targetIds")));
        if (payload.has("candidateProfiles") && !payload.path("candidateProfiles").isNull()) {
            if (!payload.path("candidateProfiles").isArray()) {
                throw new NonRetryableEventException("candidateProfiles必须是数组");
            }
            command.setCandidateProfiles(
                    objectMapper.convertValue(
                            payload.path("candidateProfiles"),
                            new TypeReference<List<PlnSchedulingCandidateProfileDTO>>() {
                            }));
        }
        PlnSchedulingScenarioDTO result = schedulingEngineService.runScenario(command);
        require(result != null && result.getScenario() != null, "多候选排产情景未成功生成");
        PlnSchedulingScenarioEntity scenario = result.getScenario();
        long feasible = result.getCandidates().stream()
                .filter(item -> Boolean.TRUE.equals(item.getCandidateFeasible())).count();
        return new HandlerResult("SCHEDULING_SCENARIO", scenario.getId(), scenario.getScenarioNo(),
                "同一制造事件已生成" + scenario.getCandidateCount() + "个真实有限产能候选，其中可行"
                        + feasible + "个；已完成八目标比较，选择和正式锁定仍需计划员A2/A3复核");
    }

    private HandlerResult evaluateQuality(JsonNode payload) {
        Long receiptId = requiredLong(payload, "receiptId");
        Long issueId = requiredLong(payload, "issueId");
        PlnIssueCallbackReceiptEntity receipt = callbackReceiptMapper.selectById(receiptId);
        PlnIssueEntity issue = issueMapper.selectById(issueId);
        if (receipt == null || issue == null || !Objects.equals(receipt.getIssueId(), issueId))
            throw new NonRetryableEventException("MES回执或生产计划不存在/关联不一致");
        qualityLocalReplanService.captureCallback(receipt, issue);
        return new HandlerResult("ISSUE_CALLBACK_RECEIPT", receiptId, receipt.getRequestId(),
                "已同步补产履约并完成质量偏差阈值评估，候选发布仍需人工复核");
    }

    /**
     * 将QMS权威处置事实与MES数量回执对齐后复用同一质量评估入口。
     * MES回执可能先于QMS Outbox到达，本处理器只认精确请求号，不按计划号猜测或重复累计数量。
     */
    private HandlerResult reconcileQualitySource(PlnAutomationEventEntity event, JsonNode payload) {
        Long issueId = requiredLong(payload, "issueId");
        String issueNo = requiredText(payload, "issueNo");
        String callbackRequestId = requiredText(payload, "mesCallbackRequestId");
        String mappingFingerprint = requiredText(payload, "mappingFingerprint");
        String qmsFactFingerprint = requiredText(payload, "qmsFactFingerprint");
        BigDecimal allocatedQuantity = requiredDecimal(payload, "allocatedQuantity");
        String dispositionKind = requiredText(payload, "dispositionKind").toUpperCase(Locale.ROOT);
        if (!Set.of("ISOLATE", "RELEASE", "CONCESSION", "REWORK", "SMASH")
                .contains(dispositionKind)) {
            throw new NonRetryableEventException("QMS直连排产事件只支持隔离、放行、让步、返工和已批准报废事实");
        }
        if (!"PLN_ISSUE".equals(event.getAggregateType())
                || !Objects.equals(String.valueOf(issueId), event.getAggregateId())) {
            throw new NonRetryableEventException("QMS事件聚合对象与来源计划不一致");
        }
        if (!"QMS".equals(event.getSourceSystem())) {
            throw new NonRetryableEventException("质量源对账事件必须来自QMS");
        }
        if (mappingFingerprint.length() != 64 || qmsFactFingerprint.length() != 64
                || allocatedQuantity.signum() <= 0) {
            throw new NonRetryableEventException("QMS质量源映射指纹、事实指纹或分配数量不合法");
        }

        PlnIssueCallbackReceiptEntity receipt = callbackReceiptMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlnIssueCallbackReceiptEntity>()
                        .eq(PlnIssueCallbackReceiptEntity::getRequestId, callbackRequestId));
        PlnIssueEntity issue = issueMapper.selectById(issueId);
        if (receipt == null) {
            throw new IllegalStateException("MES质量数量回执尚未到达PLAN：" + callbackRequestId);
        }
        if (issue == null || !Objects.equals(receipt.getIssueId(), issueId)
                || !Objects.equals(receipt.getIssueNo(), issueNo)
                || !Objects.equals(issue.getIssueNo(), issueNo)) {
            throw new NonRetryableEventException("QMS来源计划与MES数量回执关联不一致");
        }
        if (!Objects.equals(event.getFactoryCode(), issue.getFactoryCode())
                || !Objects.equals(event.getFactoryCode(), receipt.getFactoryCode())) {
            throw new NonRetryableEventException("QMS质量事实、MES回执与PLAN计划跨工厂");
        }
        BigDecimal receiptQuantity = switch (dispositionKind) {
            case "ISOLATE" -> receipt.getQualityIsolatedQuantity();
            case "RELEASE", "CONCESSION" -> receipt.getQualityReleasedQuantity();
            case "REWORK" -> receipt.getReworkQuantity();
            case "SMASH" -> receipt.getScrapQuantity();
            default -> null;
        };
        if (receiptQuantity == null || receiptQuantity.signum() <= 0
                || receiptQuantity.compareTo(allocatedQuantity) != 0) {
            throw new NonRetryableEventException("MES数量回执与QMS【" + dispositionKind + "】逐计划分配量不一致");
        }

        // captureCallback使用CALLBACK:<MES请求号>作为永久幂等评价键；即使MES事件已经先处理，也不会重复建案。
        qualityLocalReplanService.captureCallback(receipt, issue);
        return new HandlerResult(
                "ISSUE_CALLBACK_RECEIPT",
                receipt.getId(),
                receipt.getRequestId(),
                "QMS【"
                        + dispositionKind
                        + "】事实已与MES数量回执及来源计划完成对账并复用质量偏差评估"
                        + "（映射="
                        + abbreviate(mappingFingerprint)
                        + "，事实="
                        + abbreviate(qmsFactFingerprint)
                        + "）；补产或局部重排仍按既有A2/A3闸门执行");
    }

    private HandlerResult reviewDeviceRecovery(PlnAutomationEventEntity event, JsonNode payload) {
        var result = deviceRecoveryEventHandler.review(event, payload);
        return new HandlerResult("DEVICE_RECOVERY_REVIEW", null, null, result);
    }

    private HandlerResult observeRepairResume(PlnAutomationEventEntity event, JsonNode payload) {
        var result = repairResumeEventHandler.observe(event, payload);
        return new HandlerResult(
                "REPAIR_RESUME_OBSERVATION",
                result.getId(),
                String.valueOf(result.getMesOutboxId()),
                "MES真实复工区间已按维修停工/批准/MDM回执回写PLAN；计划对账状态="
                        + result.getMatchStatus()
                        + "，不替代PLAN排程复核或现场安全复机确认");
    }

    private HandlerResult applyCompletionReopen(PlnAutomationEventEntity event, JsonNode payload) {
        PlnCompletionReopenEventHandler.ApplyResult result = completionReopenEventHandler.apply(event, payload);
        PlnCompletionReopenReceiptEntity first = result.receipts().isEmpty() ? null : result.receipts().get(0);
        return new HandlerResult(result.receipts().size() == 1
                ? "PLAN_COMPLETION_REOPEN_RECEIPT" : "PLAN_COMPLETION_REOPEN_RECEIPT_BATCH",
                first == null ? null : first.getId(), first == null ? null : first.getIssueNo(), result.summary());
    }

    private HandlerResult assessResource(PlnAutomationEventEntity event, JsonNode payload) {
        String resourceType = requiredText(payload, "resourceType").toUpperCase(Locale.ROOT);
        if (!Set.of("DEVICE", "TOOLING", "EMPLOYEE").contains(resourceType))
            throw new NonRetryableEventException("资源类型只允许DEVICE/TOOLING/EMPLOYEE");
        String resourceCode = requiredText(payload, "resourceCode");
        if ("MDM".equals(event.getSourceSystem())) {
            validateMdmResourceSource(event, payload, resourceType, resourceCode);
        }
        LocalDateTime eventStart =
                parseDateTime(requiredText(payload, "eventStartTime"), "eventStartTime");
        boolean openEnded = payload.path("openEnded").asBoolean(false);
        LocalDateTime expectedRecovery =
                openEnded
                        ? null
                        : parseDateTime(
                        requiredText(payload, "expectedRecoveryTime"),
                        "expectedRecoveryTime");
        if (!openEnded && !expectedRecovery.isAfter(eventStart))
            throw new NonRetryableEventException("预计恢复时间必须晚于资源异常开始时间");
        String reason = requiredText(payload, "reason");
        Long requestedIssueId = optionalLong(payload, "issueId");
        List<Long> issueIds = requestedIssueId == null
                ? findAffectedIssueIds(event, resourceType, resourceCode, eventStart, expectedRecovery, openEnded)
                : List.of(requestedIssueId);
        if (issueIds.isEmpty()) {
            return new HandlerResult("RESOURCE_IMPACT_ASSESSMENT", null, null,
                    "已核对正式锁定排程，当前异常窗口未影响任何已排工序，无需生成局部重排候选");
        }
        int maxAffectedIssues =
                optionalInt(payload, "maxAffectedIssues") == null
                        ? 50
                        : optionalInt(payload, "maxAffectedIssues");
        if (maxAffectedIssues < 1 || maxAffectedIssues > 200)
            throw new NonRetryableEventException("maxAffectedIssues必须在1到200之间");
        if (issueIds.size() > maxAffectedIssues)
            throw new NonRetryableEventException(
                    "资源异常影响计划数" + issueIds.size() + "超过事件上限" + maxAffectedIssues + "，需人工分批评估");

        List<PlanResourceLocalReplanDTO.Scenario> results = new ArrayList<>();
        for (Long issueId : issueIds) {
            PlanResourceLocalReplanDTO.AssessCommand command = new PlanResourceLocalReplanDTO.AssessCommand();
            command.setIssueId(issueId);
            command.setRequestId("AUTO:" + event.getEventNo() + ':' + issueId);
            command.setResourceType(resourceType);
            command.setResourceCode(resourceCode);
            command.setEventStartTime(eventStart);
            command.setExpectedRecoveryTime(expectedRecovery);
            command.setOpenEnded(openEnded);
            command.setReason(reason);
            results.add(resourceLocalReplanService.assess(command));
        }
        PlanResourceLocalReplanDTO.Scenario first = results.get(0);
        String scenarioNos = results.stream().map(item -> item.getScenario().getScenarioNo())
                .filter(Objects::nonNull).limit(20).collect(Collectors.joining(","));
        return new HandlerResult(
                results.size() == 1 ? "RESOURCE_REPLAN_SCENARIO" : "RESOURCE_REPLAN_SCENARIO_BATCH",
                first.getScenario().getId(),
                first.getScenario().getScenarioNo(),
                "已从正式锁定排程识别"
                        + issueIds.size()
                        + "张受影响计划并生成"
                        + results.size()
                        + "个"
                        + (openEnded ? "开放式" : "定时")
                        + "资源异常三候选情景（"
                        + scenarioNos
                        + "），需先A2选择唯一候选，再由A3人工复核发布");
    }

    private void validateMdmResourceSource(PlnAutomationEventEntity event, JsonNode payload,
                                           String resourceType, String resourceCode) {
        if (!"MDM_RESOURCE".equals(event.getAggregateType())
                || !Objects.equals(resourceType + ':' + resourceCode, event.getAggregateId())) {
            throw new NonRetryableEventException("MDM资源事件聚合对象与资源不一致");
        }
        String sourceType = requiredText(payload, "sourceType");
        String fingerprint = requiredText(payload, "sourceFingerprint");
        boolean openEnded = payload.path("openEnded").asBoolean(false);
        boolean supported =
                ("TOOLING".equals(resourceType)
                        && Set.of(
                                "MDM_TOOLING_UNAVAILABLE_WINDOW",
                                "MDM_TOOLING_MASTER_STATUS")
                        .contains(sourceType))
                        || ("EMPLOYEE".equals(resourceType)
                        && Set.of(
                                "MDM_EMPLOYEE_CALENDAR_EXCEPTION",
                                "MDM_EMPLOYEE_QUALIFICATION_INVALID")
                        .contains(sourceType));
        boolean modeMatches =
                Set.of("MDM_TOOLING_MASTER_STATUS", "MDM_EMPLOYEE_QUALIFICATION_INVALID")
                        .contains(sourceType)
                        == openEnded;
        if (!supported || !modeMatches || fingerprint.length() != 64) {
            throw new NonRetryableEventException("MDM资源事件来源类型或事实指纹不合法");
        }
    }

    /**
     * 权威源只需提供资源事实；受影响计划必须由PLAN正式锁定排程反查，禁止来源系统猜测。
     */
    private List<Long> findAffectedIssueIds(PlnAutomationEventEntity event, String resourceType, String resourceCode,
                                            LocalDateTime eventStart, LocalDateTime expectedRecovery,
                                            boolean openEnded) {
        List<PlnSchedulingOperationEntity> operations = schedulingOperationMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .eq(PlnSchedulingOperationEntity::getFactoryCode, event.getFactoryCode())
                        .eq(PlnSchedulingOperationEntity::getLockStatus, true)
                        .lt(!openEnded, PlnSchedulingOperationEntity::getScheduledStartTime, expectedRecovery)
                        .gt(PlnSchedulingOperationEntity::getScheduledEndTime, eventStart)
                        .orderByAsc(PlnSchedulingOperationEntity::getScheduledStartTime)
                        .orderByAsc(PlnSchedulingOperationEntity::getId));
        Map<Long, PlnIssueEntity> issues = new LinkedHashMap<>();
        for (PlnSchedulingOperationEntity operation : operations) {
            if (!usesResource(operation, resourceType, resourceCode) || operation.getIssueId() == null) continue;
            PlnIssueEntity issue = issueMapper.selectById(operation.getIssueId());
            if (issue == null || !Boolean.TRUE.equals(issue.getLockStatus())
                    || !Objects.equals(issue.getLockTaskId(), operation.getTaskId())
                    || !Objects.equals(issue.getFactoryCode(), event.getFactoryCode())) continue;
            issues.putIfAbsent(issue.getId(), issue);
        }
        return new ArrayList<>(issues.keySet());
    }

    private boolean usesResource(PlnSchedulingOperationEntity operation, String resourceType, String resourceCode) {
        return switch (resourceType) {
            case "DEVICE" -> Objects.equals(resourceCode, operation.getDeviceCode());
            case "TOOLING" -> containsCode(operation.getToolingCodes(), resourceCode);
            case "EMPLOYEE" -> containsCode(operation.getEmployeeCode(), resourceCode);
            default -> false;
        };
    }

    private boolean containsCode(String codes, String expected) {
        if (codes == null) return false;
        return java.util.Arrays.stream(codes.split(","))
                .map(String::trim)
                .anyMatch(expected::equals);
    }

    private HandlerResult proposeShortage(PlnAutomationEventEntity event, JsonNode payload) {
        if ("PMS".equals(event.getSourceSystem())) return proposePmsCommitmentShortage(event, payload);
        PlanShortageLocalReplanDTO.ProposeCommand command = new PlanShortageLocalReplanDTO.ProposeCommand();
        command.setRequestId("AUTO:" + event.getEventNo());
        command.setFactoryCode(event.getFactoryCode());
        command.setMrpId(requiredLong(payload, "mrpId"));
        command.setIssueId(optionalLong(payload, "issueId"));
        if ("WMS".equals(event.getSourceSystem()))
            validateWmsShortageSource(event, payload, command.getMrpId());
        else if (command.getIssueId() == null)
            throw new NonRetryableEventException("非WMS缺料事件必须明确issueId");
        command.setExpectedSupplyTime(
                parseDateTime(requiredText(payload, "expectedSupplyTime"), "expectedSupplyTime"));
        command.setAffectedProcessCodes(textList(payload.get("affectedProcessCodes")));
        command.setFreezeMinutes(optionalInt(payload, "freezeMinutes"));
        command.setMaxDelayMinutes(optionalInt(payload, "maxDelayMinutes"));
        command.setReason(requiredText(payload, "reason"));
        PlanShortageLocalReplanDTO.Scenario result = shortageLocalReplanService.propose(command);
        return new HandlerResult("SHORTAGE_REPLAN_SCENARIO", result.getScenario().getId(),
                result.getScenario().getScenarioNo(), "已生成3个缺料局部重排候选，需先比较选择再A3发布");
    }

    /**
     * PMS只发布采购计划、生产需求号、物料和正式承诺事实；目标MRP与锁定计划由PLAN实时反查。
     * 这样即使MRP重算产生新记录，采购侧也不会把事件错误绑定到历史MRP主键。
     */
    private HandlerResult proposePmsCommitmentShortage(PlnAutomationEventEntity event, JsonNode payload) {
        String pmsPlanNo = requiredText(payload, "pmsPlanNo");
        String planDemandNo = requiredText(payload, "planDemandNo");
        String materialCode = requiredText(payload, "materialCode");
        String sourceType = requiredText(payload, "sourceType");
        String sourceFingerprint = requiredText(payload, "sourceFingerprint");
        if (!"PMS_PLAN".equals(event.getAggregateType()) || !Objects.equals(pmsPlanNo, event.getAggregateId()))
            throw new NonRetryableEventException("PMS承诺事件聚合对象与采购计划不一致");
        if (!Set.of("PMS_DELIVERY_COMMITMENT_ACTIVATED", "PMS_DELIVERY_COMMITMENT_CANCELLED",
                "PMS_ORDER_REMAINING_CANCELLED", "PMS_ORDER_AMENDED").contains(sourceType)
                || sourceFingerprint.length() != 64)
            throw new NonRetryableEventException("PMS承诺事件来源类型或事实指纹不合法");
        LocalDateTime expectedSupplyTime =
                parseDateTime(requiredText(payload, "expectedSupplyTime"), "expectedSupplyTime");
        String reason = requiredText(payload, "reason");
        List<PlanMatMrpEntity> candidates = mrpMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlanMatMrpEntity>()
                        .eq(PlanMatMrpEntity::getFactoryCode, event.getFactoryCode())
                        .eq(PlanMatMrpEntity::getPlanDemandNo, planDemandNo)
                        .eq(PlanMatMrpEntity::getMatCode, materialCode)
                        .gt(PlanMatMrpEntity::getRemainingDemandNum, BigDecimal.ZERO)
                        .in(PlanMatMrpEntity::getReadinessStatus,
                                "SHORTAGE", "SUBSTITUTE_PENDING", "PARTIAL_SHORTAGE")
                        .orderByAsc(PlanMatMrpEntity::getId));
        if (candidates.isEmpty()) {
            return new HandlerResult("PMS_COMMITMENT_IMPACT_ASSESSMENT", null, pmsPlanNo,
                    "已按生产需求号和物料复核当前MRP，现无有效缺料记录，无需生成局部重排候选");
        }
        if (candidates.size() > 50)
            throw new NonRetryableEventException("PMS承诺变化命中MRP记录超过50条，需人工核查需求拆分关系");
        List<PlanShortageLocalReplanDTO.Scenario> proposals = new ArrayList<>();
        for (PlanMatMrpEntity mrp : candidates) {
            PlnIssueEntity issue = lockedRootIssue(mrp, event.getFactoryCode());
            if (issue == null) continue;
            PlanShortageLocalReplanDTO.ProposeCommand command = new PlanShortageLocalReplanDTO.ProposeCommand();
            command.setRequestId("AUTO:" + event.getEventNo() + ':' + mrp.getId());
            command.setFactoryCode(event.getFactoryCode());
            command.setMrpId(mrp.getId());
            command.setIssueId(issue.getId());
            command.setExpectedSupplyTime(expectedSupplyTime);
            command.setAffectedProcessCodes(textList(payload.get("affectedProcessCodes")));
            command.setFreezeMinutes(optionalInt(payload, "freezeMinutes"));
            command.setMaxDelayMinutes(optionalInt(payload, "maxDelayMinutes"));
            command.setReason(reason + "；PMS采购计划=" + pmsPlanNo);
            proposals.add(shortageLocalReplanService.propose(command));
        }
        if (proposals.isEmpty()) {
            return new HandlerResult("PMS_COMMITMENT_IMPACT_ASSESSMENT", null, pmsPlanNo,
                    "当前受影响MPS需求桶尚无正式锁定排程，承诺事实已收件但无需生成局部重排候选");
        }
        PlanShortageLocalReplanDTO.Scenario first = proposals.get(0);
        List<Long> ids = proposals.stream().map(item -> item.getScenario().getId()).toList();
        String summary = "已生成" + proposals.size() + "组缺料重排候选，等待计划员比较方案";
        // 收件头保存首组，成功履历在同一事务保存其余组的确切主键。最多 49 个附加主键。
        return new HandlerResult(ids.size() == 1 ? "SHORTAGE_REPLAN_SCENARIO" : "SHORTAGE_REPLAN_SCENARIO_BATCH",
                ids.get(0), first.getScenario().getScenarioNo(), summary,
                ids.size() == 1 ? summary : PurchaseReplanTargets.encode(ids));
    }

    /**
     * 按MRP的独立计划来源定位根生产计划。MPS拆量后同一需求会有多个根计划，禁止再按需求号取第一条。
     */
    private PlnIssueEntity lockedRootIssue(PlanMatMrpEntity mrp, String factoryCode) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlnIssueEntity> query =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlnIssueEntity>()
                        .eq(PlnIssueEntity::getFactoryCode, factoryCode)
                        .isNull(PlnIssueEntity::getSourceMrpId)
                        .eq(PlnIssueEntity::getLockStatus, true)
                        .isNotNull(PlnIssueEntity::getLockTaskId)
                        .notIn(PlnIssueEntity::getStatus, "3", "4");
        if (mrp.getMpsLineId() != null) {
            query.eq(PlnIssueEntity::getMpsLineId, mrp.getMpsLineId());
        } else {
            query.and(source -> source.eq(PlnIssueEntity::getSourceDemandNo, mrp.getPlanDemandNo())
                    .or().and(legacy -> legacy.isNull(PlnIssueEntity::getSourceDemandNo)
                            .eq(PlnIssueEntity::getIssueNo, mrp.getPlanDemandNo())));
        }
        List<PlnIssueEntity> issues = issueMapper.selectList(query.orderByAsc(PlnIssueEntity::getId));
        if (issues.size() > 1) {
            throw new NonRetryableEventException("MRP计划来源命中多个锁定根生产计划，禁止猜测承诺变化影响范围：" + mrp.getId());
        }
        return issues.isEmpty() ? null : issues.get(0);
    }

    private void validateWmsShortageSource(PlnAutomationEventEntity event, JsonNode payload, Long mrpId) {
        if (!"PLAN_MRP".equals(event.getAggregateType())
                || !Objects.equals(String.valueOf(mrpId), event.getAggregateId())) {
            throw new NonRetryableEventException("WMS缺料事件聚合对象与MRP不一致");
        }
        String sourceType = requiredText(payload, "sourceType");
        if (!Set.of("WMS_AGENT_TRANSFER_CANCELLED", "WMS_AGENT_TRANSFER_SHORT_RECEIPT")
                .contains(sourceType)) {
            throw new NonRetryableEventException("WMS缺料事件来源类型不受支持");
        }
        String sourceFingerprint = requiredText(payload, "sourceFingerprint");
        String issueNo = requiredText(payload, "issueNo");
        String materialCode = requiredText(payload, "materialCode");
        BigDecimal shortageQuantity = requiredDecimal(payload, "shortageQuantity");
        if (sourceFingerprint.length() != 64 || shortageQuantity.signum() <= 0) {
            throw new NonRetryableEventException("WMS缺料事实指纹或缺口数量不合法");
        }
        PlanMatMrpEntity mrp = mrpMapper.selectById(mrpId);
        if (mrp == null || !Objects.equals(mrp.getFactoryCode(), event.getFactoryCode())
                || !Objects.equals(mrp.getMatCode(), materialCode)) {
            throw new NonRetryableEventException("WMS缺料事实与PLAN MRP的工厂或物料不一致");
        }
        if (mrp.getMpsLineId() != null) {
            PlnIssueEntity issue = issueMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlnIssueEntity>()
                            .eq(PlnIssueEntity::getFactoryCode, event.getFactoryCode())
                            .eq(PlnIssueEntity::getIssueNo, issueNo)
                            .eq(PlnIssueEntity::getMpsLineId, mrp.getMpsLineId())
                            .isNull(PlnIssueEntity::getSourceMrpId)
                            .last("LIMIT 1"));
            if (issue == null) {
                throw new NonRetryableEventException("WMS缺料事实中的生产计划号不属于该MRP的MPS需求桶");
            }
        } else if (!Objects.equals(mrp.getPlanDemandNo(), issueNo)) {
            throw new NonRetryableEventException("WMS缺料事实中的生产计划号与MRP需求号不一致");
        }
    }

    private HandlerResult assessInsertion(PlnAutomationEventEntity event, JsonNode payload) {
        PlanInsertionLocalReplanDTO.AssessCommand command = new PlanInsertionLocalReplanDTO.AssessCommand();
        command.setInsertionIssueId(requiredLong(payload, "insertionIssueId"));
        command.setRequestId("AUTO:" + event.getEventNo());
        command.setUrgencyLevel(requiredText(payload, "urgencyLevel").toUpperCase());
        command.setRequestedStartTime(
                parseDateTime(requiredText(payload, "requestedStartTime"), "requestedStartTime"));
        command.setBusinessReason(requiredText(payload, "businessReason"));
        PlanInsertionLocalReplanDTO.Scenario result = insertionLocalReplanService.assess(command);
        String caseNos = result.getCandidates().stream().map(PlanInsertionLocalReplanDTO.CaseDetail::getCaseNo)
                .collect(Collectors.joining(","));
        return new HandlerResult("INSERTION_REPLAN_SCENARIO", result.getScenario().getId(),
                result.getScenario().getScenarioNo(), "已生成紧急插单跨工单三候选（" + caseNos
                + "），需先A2人工选择再A3发布");
    }

    private JsonNode parse(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception ex) {
            throw new NonRetryableEventException("事件事实载荷无法解析", ex);
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank())
            throw new NonRetryableEventException("事件载荷缺少字段：" + field);
        return value.asText().trim();
    }

    private Long requiredLong(JsonNode node, String field) {
        try {
            return Long.valueOf(requiredText(node, field));
        } catch (NumberFormatException ex) {
            throw new NonRetryableEventException("事件字段必须为长整数：" + field);
        }
    }

    private BigDecimal requiredDecimal(JsonNode node, String field) {
        try {
            return new BigDecimal(requiredText(node, field));
        } catch (NumberFormatException ex) {
            throw new NonRetryableEventException("事件字段必须为数值：" + field);
        }
    }

    private Long optionalLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) return null;
        try {
            return Long.valueOf(value.asText().trim());
        } catch (Exception ex) {
            throw new NonRetryableEventException("事件字段必须为长整数：" + field);
        }
    }

    private Integer optionalInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private LocalDate parseDate(String value, String field) {
        try {
            return LocalDate.parse(value);
        } catch (Exception ex) {
            throw new NonRetryableEventException("事件日期格式不合法：" + field);
        }
    }

    /** PMS 共用序列化器使用空格，其他生产者可发送 ISO 时间；保留精度并严格校验日期和时分。 */
    private LocalDateTime parseDateTime(String value, String field) {
        try {
            String iso = value.length() > 10 && value.charAt(10) == ' '
                    ? value.substring(0, 10) + 'T' + value.substring(11) : value;
            return LocalDateTime.parse(iso);
        } catch (Exception ex) {
            throw new NonRetryableEventException("事件时间格式不合法：" + field);
        }
    }

    private List<String> textList(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new NonRetryableEventException("affectedProcessCodes必须是数组");
        List<String> values = new ArrayList<>();
        node.forEach(v -> {
            if (!v.asText().isBlank()) values.add(v.asText().trim());
        });
        return values;
    }

    private List<Long> longList(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new NonRetryableEventException("targetIds必须是数组");
        List<Long> values = new ArrayList<>();
        node.forEach(
                value -> {
                    if (!value.canConvertToLong())
                        throw new NonRetryableEventException("targetIds必须为长整数数组");
                    values.add(value.asLong());
                });
        return values.stream().distinct().toList();
    }

    private String abbreviate(String value) {
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private void operation(PlnAutomationEventEntity event, String requestId, String type, String before,
                           String after, int attempt, String content) {
        require(
                operationMapper.insert(
                        new PlnAutomationEventOperationEntity()
                                .setEventId(event.getId())
                                .setRequestId(requestId)
                                .setOperationType(type)
                                .setBeforeStatus(before)
                                .setAfterStatus(after)
                                .setAttemptNo(attempt)
                                .setContent(content)
                                .setOperatorCode("SYSTEM")
                                .setOperatorName("系统事件处理器")
                                .setOperationTime(LocalDateTime.now())
                                .setFactoryCode(event.getFactoryCode()))
                        == 1,
                "事件执行履历保存失败");
    }

    private record HandlerResult(String targetType, Long targetId, String targetNo, String summary,
                                 String operationContent) {
        private HandlerResult(String type, Long id, String no, String summary) {
            this(type, id, no, summary, summary);
        }
    }

    public static class NonRetryableEventException extends RuntimeException {
        public NonRetryableEventException(String message) {
            super(message);
        }

        public NonRetryableEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
