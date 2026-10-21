package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.dto.MdmDeviceRecoveryEventEvidence;
import com.kitex.mom.mdm.feign.RemoteMdmDeviceRecoveryEventService;
import com.kitex.mom.plan.entity.PlnAutomationEventEntity;
import com.kitex.mom.plan.entity.PlnResourceReplanCaseEntity;
import com.kitex.mom.plan.entity.PlnResourceReplanOperationEntity;
import com.kitex.mom.plan.entity.PlnSchedulingScenarioEntity;
import com.kitex.mom.plan.mapper.PlnResourceReplanCaseMapper;
import com.kitex.mom.plan.mapper.PlnResourceReplanOperationMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingScenarioMapper;
import com.kitex.mom.tpm.dto.TpmDeviceAvailabilityQueryDTO;
import com.kitex.mom.tpm.feign.RemoteTpmDeviceAvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * MDM恢复事实只撤销旧维修窗口的未发布候选；已发布排程留人工复核，不自动回滚或复产。
 */
@Service
@RequiredArgsConstructor
public class PlnDeviceRecoveryEventHandler {
    private final RemoteMdmDeviceRecoveryEventService recovery;
    private final RemoteTpmDeviceAvailabilityService availability;
    private final PlnResourceReplanCaseMapper cases;
    private final PlnResourceReplanOperationMapper operations;
    private final PlnSchedulingScenarioMapper scenarios;
    private final ObjectMapper json;

    private static void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }

    public String review(PlnAutomationEventEntity event, JsonNode payload) {
        require("MDM".equals(event.getSourceSystem()) && "RESOURCE_AVAILABLE".equals(event.getEventType())
                && "MDM_RESOURCE".equals(event.getAggregateType()), "设备恢复事件来源或类型不一致");
        String code = required(payload, "resourceCode"), taskCode = required(payload, "taskCode");
        require("DEVICE".equals(required(payload, "resourceType")) && Objects.equals(event.getAggregateId(), "DEVICE:" + code),
                "设备恢复事件聚合对象不一致");
        Long receiptId = positiveLong(payload, "recoveryReceiptId"), verificationId = positiveLong(payload, "verificationId");
        Long taskId = positiveLong(payload, "taskId"), deviceId = positiveLong(payload, "deviceId");
        String requestFingerprint = required(payload, "requestFingerprint"), sourceFingerprint = required(payload, "sourceFingerprint");
        LocalDateTime recoveredTime;
        try {
            recoveredTime = LocalDateTime.parse(required(payload, "recoveredTime"));
        } catch (RuntimeException ex) {
            throw new CheckedException("设备恢复时刻不可解析");
        }
        require(Objects.equals(recoveredTime, event.getOccurredTime()), "设备恢复事实时间与PLAN来源不一致");
        var response = recovery.evidence(receiptId, event.getFactoryCode());
        if (response == null || !response.isOk() || response.getData() == null)
            throw new IllegalStateException("MDM设备恢复回执暂不可核验");
        MdmDeviceRecoveryEventEvidence proof = response.getData();
        require(Objects.equals(proof.getTenantId(), event.getTenantId()) && Objects.equals(proof.getFactoryCode(), event.getFactoryCode())
                        && Objects.equals(proof.getReceiptId(), receiptId) && Objects.equals(proof.getVerificationId(), verificationId)
                        && Objects.equals(proof.getTaskId(), taskId) && Objects.equals(proof.getTaskCode(), taskCode)
                        && Objects.equals(proof.getDeviceId(), deviceId) && Objects.equals(proof.getDeviceCode(), code)
                        && Objects.equals(proof.getRequestFingerprint(), requestFingerprint) && Objects.equals(proof.getSourceFingerprint(), sourceFingerprint),
                "MDM实时恢复证据与冻结事件不一致");
        if (!Objects.equals(proof.getLatestRecoveryReceiptId(), receiptId) || !Boolean.TRUE.equals(proof.getDeviceStatus())
                || !"0".equals(proof.getDeviceState()) || !"1".equals(proof.getDeviceUsable()))
            return "设备恢复事实已被后续状态覆盖；保留事件履历，不作旧排程候选失效判断";
        var query = new TpmDeviceAvailabilityQueryDTO();
        query.setDeviceCodes(List.of(code));
        LocalDateTime now = LocalDateTime.now();
        query.setStartTime(now.minusSeconds(1));
        query.setEndTime(now.plusSeconds(1));
        var windows = availability.unavailableWindows(query);
        if (windows == null || !windows.isOk() || windows.getData() == null)
            throw new IllegalStateException("TPM实时设备阻断暂不可核验");
        if (windows.getData().stream().anyMatch(w -> Objects.equals(w.getDeviceCode(), code) && !"DEVICE_STATE".equals(w.getSourceType())))
            return "设备存在新的TPM维修、保养或点检阻断；恢复事实入账但不自动使其他异常候选失效";
        var pending = cases.selectList(new LambdaQueryWrapper<PlnResourceReplanCaseEntity>()
                .eq(PlnResourceReplanCaseEntity::getTenantId, event.getTenantId())
                .eq(PlnResourceReplanCaseEntity::getFactoryCode, event.getFactoryCode())
                .eq(PlnResourceReplanCaseEntity::getResourceType, "DEVICE")
                .eq(PlnResourceReplanCaseEntity::getResourceCode, code)
                .in(PlnResourceReplanCaseEntity::getStatus, "PROPOSED", "SELECTED", "PUBLISHED")
                .le(PlnResourceReplanCaseEntity::getProposedTime, recoveredTime)
                .orderByAsc(PlnResourceReplanCaseEntity::getId).last("LIMIT 501"));
        require(pending.size() <= 500, "该设备待核对局部重排案件超过500，须计划员分批审查后再处理恢复事件");
        var scenarioIds = pending.stream().map(PlnResourceReplanCaseEntity::getScenarioId)
                .filter(Objects::nonNull).distinct().sorted().toList();
        for (Long scenarioId : scenarioIds)
            scenarios.selectOne(new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                    .eq(PlnSchedulingScenarioEntity::getId, scenarioId).last("FOR UPDATE"));
        int stale = 0, published = 0;
        Set<Long> affectedScenarios = new HashSet<>();
        for (var candidate : pending) {
            var q = new LambdaQueryWrapper<PlnResourceReplanCaseEntity>().eq(PlnResourceReplanCaseEntity::getId, candidate.getId())
                    .eq(PlnResourceReplanCaseEntity::getTenantId, event.getTenantId())
                    .eq(PlnResourceReplanCaseEntity::getFactoryCode, event.getFactoryCode()).last("FOR UPDATE");
            var current = cases.selectOne(q);
            if (current == null || !Set.of("PROPOSED", "SELECTED", "PUBLISHED").contains(current.getStatus())
                    || !containsRepairWindow(current.getResourceEvidenceSnapshot(), taskId, taskCode)) continue;
            String before = current.getStatus();
            if (!"PUBLISHED".equals(before)) {
                current.setStatus("STALE");
                require(cases.updateById(current) == 1, "旧维修窗口候选失效保存失败");
                if (current.getScenarioId() != null) affectedScenarios.add(current.getScenarioId());
                audit(event, current, "EVIDENCE_STALE", before, "STALE", "MDM恢复回执" + receiptId + "已使维修任务" + taskCode + "的旧不可用窗口候选失效；须重新评估");
                stale++;
            } else {
                require(cases.update(null, new LambdaUpdateWrapper<PlnResourceReplanCaseEntity>()
                                .eq(PlnResourceReplanCaseEntity::getId, current.getId())
                                .eq(PlnResourceReplanCaseEntity::getTenantId, event.getTenantId())
                                .eq(PlnResourceReplanCaseEntity::getFactoryCode, event.getFactoryCode())
                                .eq(PlnResourceReplanCaseEntity::getStatus, "PUBLISHED")
                                .set(PlnResourceReplanCaseEntity::getRecoveryReviewEventId, event.getId())
                                .set(PlnResourceReplanCaseEntity::getRecoveryReceiptId, receiptId)
                                .set(PlnResourceReplanCaseEntity::getRecoveryReviewStatus, "PENDING")
                                .set(PlnResourceReplanCaseEntity::getRecoveryReviewTime, LocalDateTime.now())
                                .set(PlnResourceReplanCaseEntity::getRecoveryReviewRequestId, null)
                                .set(PlnResourceReplanCaseEntity::getRecoveryReviewReason, null)
                                .set(PlnResourceReplanCaseEntity::getRecoveryReviewedBy, null)
                                .set(PlnResourceReplanCaseEntity::getRecoveryReviewedTime, null)
                                .set(PlnResourceReplanCaseEntity::getRecoveryReviewEvidenceFingerprint, null)
                                .set(PlnResourceReplanCaseEntity::getRecoveryReplacementTaskId, null)
                                .set(PlnResourceReplanCaseEntity::getRecoveryReplacementCaseId, null)) == 1,
                        "已发布排程恢复复核待办保存失败");
                audit(event, current, "RECOVERY_REVIEW", before, before, "MDM恢复回执" + receiptId + "已到达；已发布排程不自动回滚，计划员须核对复产与改派资源");
                published++;
            }
        }
        for (Long scenarioId : affectedScenarios) {
            var live = cases.selectCount(new LambdaQueryWrapper<PlnResourceReplanCaseEntity>()
                    .eq(PlnResourceReplanCaseEntity::getTenantId, event.getTenantId())
                    .eq(PlnResourceReplanCaseEntity::getFactoryCode, event.getFactoryCode())
                    .eq(PlnResourceReplanCaseEntity::getScenarioId, scenarioId)
                    .in(PlnResourceReplanCaseEntity::getStatus, "PROPOSED", "SELECTED", "PUBLISHED"));
            if (live == 0) {
                var scenario = scenarios.selectById(scenarioId);
                if (scenario != null && Set.of("EVALUATED", "SELECTED").contains(scenario.getScenarioStatus())) {
                    scenario.setScenarioStatus("FAILED");
                    require(scenarios.updateById(scenario) == 1, "旧维修情景失效保存失败");
                }
            }
        }
        return "MDM设备资格恢复已与当前TPM阻断核对：使" + stale + "个未发布旧维修窗口候选失效，" + published + "个已发布排程留计划员复核；未自动开机、撤销派工或复产";
    }

    private boolean containsRepairWindow(String raw, Long taskId, String taskCode) {
        if (StrUtil.isBlank(raw)) return false;
        try {
            var facts = json.readTree(raw).path("authorityFacts");
            if (!facts.isArray()) return false;
            for (var w : facts)
                if ("REPAIR_TASK".equals(w.path("sourceType").asText())
                        && taskCode.equals(w.path("sourceCode").asText())
                        && taskId.toString().equals(w.path("sourceId").asText())) return true;
            return false;
        } catch (Exception ex) {
            throw new CheckedException("资源重排案件冻结的设备窗口证据无法解析");
        }
    }

    private void audit(PlnAutomationEventEntity event, PlnResourceReplanCaseEntity c, String action, String before, String after, String reason) {
        var op = new PlnResourceReplanOperationEntity().setCaseId(c.getId())
                .setRequestId("RECOVERY:" + event.getId() + ":" + c.getId()).setOperationType(action).setBeforeStatus(before).setAfterStatus(after)
                .setContent(reason).setOperatorCode("SYSTEM").setOperatorName("PLAN设备恢复事件处理器")
                .setOperationTime(LocalDateTime.now()).setFactoryCode(c.getFactoryCode());
        op.setTenantId(event.getTenantId());
        require(operations.insert(op) == 1, "设备恢复PLAN案件核对履历保存失败");
    }

    private String required(JsonNode p, String key) {
        String value = p.path(key).asText();
        require(StrUtil.isNotBlank(value), "设备恢复事件缺少" + key);
        return value;
    }

    private Long positiveLong(JsonNode p, String key) {
        try {
            long value = Long.parseLong(required(p, key));
            require(value > 0, "设备恢复事件" + key + "无效");
            return value;
        } catch (NumberFormatException ex) {
            throw new CheckedException("设备恢复事件" + key + "不是有效ID");
        }
    }
}
