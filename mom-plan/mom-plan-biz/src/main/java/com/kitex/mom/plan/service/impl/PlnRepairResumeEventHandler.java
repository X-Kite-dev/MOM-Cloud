package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.api.feign.RemoteMesRepairResumeEventService;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesApsLocalScheduleDTO;
import com.kitex.mom.mes.dto.MesRepairResumeEvidence;
import com.kitex.mom.plan.entity.PlnAutomationEventEntity;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnRepairResumeObservation;
import com.kitex.mom.plan.entity.PlnResourceReplanCaseEntity;
import com.kitex.mom.plan.mapper.PlnIssueMapper;
import com.kitex.mom.plan.mapper.PlnRepairResumeObservationMapper;
import com.kitex.mom.plan.mapper.PlnResourceReplanCaseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * MES真实复工事件只写PLAN观察账，不冒充排程复核或MES复产审批。
 */
@Service
@RequiredArgsConstructor
public class PlnRepairResumeEventHandler {
    private final RemoteMesRepairResumeEventService remoteEvidence;
    private final RemoteMesTaskService remoteMesTasks;
    private final PlnRepairResumeObservationMapper observations;
    private final PlnIssueMapper issues;
    private final PlnResourceReplanCaseMapper cases;

    private static Long positive(JsonNode node, String name) {
        JsonNode value = node.path(name);
        long id = value.isIntegralNumber() ? value.asLong() : -1;
        require(id > 0, "MES真实复工事件字段" + name + "必须为正整数");
        return id;
    }

    private static String required(JsonNode node, String name) {
        String value = node.path(name).asText();
        require(StrUtil.isNotBlank(value), "MES真实复工事件字段" + name + "不能为空");
        return value;
    }

    private static LocalDateTime resumeTime(JsonNode payload) {
        try {
            return LocalDateTime.parse(required(payload, "resumedTime"));
        } catch (RuntimeException ex) {
            throw new CheckedException("MES真实复工时刻不可解析");
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }

    public PlnRepairResumeObservation observe(PlnAutomationEventEntity event, JsonNode payload) {
        require("MES".equals(event.getSourceSystem()) && "MES_REPAIR_RESUMED".equals(event.getEventType())
                        && "MES_REPAIR_RESUME".equals(event.getAggregateType()),
                "真实复工事件来源或类型不一致");
        Long outboxId = positive(payload, "outboxId");
        require(Objects.equals(event.getEventId(), "MES-REPAIR-RESUME:" + positive(payload, "segmentId"))
                        && Objects.equals(event.getAggregateId(), String.valueOf(positive(payload, "segmentId"))),
                "MES复工事件ID与加工区间不一致");
        var response = remoteEvidence.evidence(outboxId, event.getFactoryCode());
        if (response == null || !response.isOk() || response.getData() == null)
            throw new IllegalStateException("MES真实复工源回执暂不可核验");
        MesRepairResumeEvidence proof = response.getData();
        require(Boolean.TRUE.equals(proof.getSourceValid())
                        && Objects.equals(proof.getTenantId(), event.getTenantId())
                        && Objects.equals(proof.getFactoryCode(), event.getFactoryCode())
                        && Objects.equals(proof.getOutboxId(), outboxId)
                        && Objects.equals(proof.getEventId(), event.getEventId())
                        && Objects.equals(proof.getStopId(), positive(payload, "stopId"))
                        && Objects.equals(proof.getReleaseId(), positive(payload, "releaseId"))
                        && Objects.equals(proof.getArrangeId(), positive(payload, "arrangeId"))
                        && Objects.equals(proof.getSegmentId(), positive(payload, "segmentId"))
                        && Objects.equals(proof.getRecoveryReceiptId(), positive(payload, "recoveryReceiptId"))
                        && Objects.equals(proof.getPlanNo(), required(payload, "planNo"))
                        && Objects.equals(proof.getRouteCode(), required(payload, "routeCode"))
                        && Objects.equals(proof.getDeviceCode(), required(payload, "deviceCode"))
                        && Objects.equals(proof.getResumedTime(), event.getOccurredTime())
                        && Objects.equals(proof.getResumedTime(), resumeTime(payload)),
                "MES真实复工来源、回执、计划或区间时刻与PLAN收件载荷不一致");
        PlnRepairResumeObservation duplicate = observations.selectOne(new LambdaQueryWrapper<PlnRepairResumeObservation>()
                .eq(PlnRepairResumeObservation::getTenantId, event.getTenantId())
                .eq(PlnRepairResumeObservation::getMesOutboxId, outboxId).last("LIMIT 1"));
        if (duplicate != null) {
            require(Objects.equals(duplicate.getInboxId(), event.getId()), "MES复工来源已被其他事件占用");
            return duplicate;
        }
        PlnIssueEntity issue = issues.selectOne(new LambdaQueryWrapper<PlnIssueEntity>()
                .eq(PlnIssueEntity::getTenantId, event.getTenantId())
                .eq(PlnIssueEntity::getFactoryCode, event.getFactoryCode())
                .eq(PlnIssueEntity::getIssueNo, proof.getPlanNo()).last("LIMIT 1"));
        PlnResourceReplanCaseEntity sourceCase = issue == null ? null : cases.selectOne(
                new LambdaQueryWrapper<PlnResourceReplanCaseEntity>()
                        .eq(PlnResourceReplanCaseEntity::getTenantId, event.getTenantId())
                        .eq(PlnResourceReplanCaseEntity::getFactoryCode, event.getFactoryCode())
                        .eq(PlnResourceReplanCaseEntity::getIssueId, issue.getId())
                        .eq(PlnResourceReplanCaseEntity::getResourceType, "DEVICE")
                        .eq(PlnResourceReplanCaseEntity::getResourceCode, proof.getDeviceCode())
                        .eq(PlnResourceReplanCaseEntity::getRecoveryReceiptId, proof.getRecoveryReceiptId())
                        .eq(PlnResourceReplanCaseEntity::getStatus, "PUBLISHED")
                        .orderByDesc(PlnResourceReplanCaseEntity::getId).last("LIMIT 1"));
        var stateResponse = remoteMesTasks.apsOperationStates(proof.getPlanNo(), event.getFactoryCode());
        if (stateResponse == null || !stateResponse.isOk() || stateResponse.getData() == null)
            throw new IllegalStateException("MES当前计划执行指纹暂不可读取");
        MesApsLocalScheduleDTO.StateBundle mes = stateResponse.getData();
        require(Objects.equals(mes.getPlanNo(), proof.getPlanNo())
                        && Objects.equals(mes.getFactoryCode(), event.getFactoryCode())
                        && StrUtil.isNotBlank(mes.getExecutionFingerprint()),
                "MES当前计划执行指纹与维修复工来源不一致");
        PlnRepairResumeObservation result = new PlnRepairResumeObservation();
        result.setTenantId(event.getTenantId());
        result.setFactoryCode(event.getFactoryCode());
        result.setInboxId(event.getId());
        result.setMesOutboxId(outboxId);
        result.setStopId(proof.getStopId());
        result.setReleaseId(proof.getReleaseId());
        result.setArrangeId(proof.getArrangeId());
        result.setSegmentId(proof.getSegmentId());
        result.setRecoveryReceiptId(proof.getRecoveryReceiptId());
        result.setIssueId(issue == null ? null : issue.getId());
        result.setResourceCaseId(sourceCase == null ? null : sourceCase.getId());
        result.setPlanNo(proof.getPlanNo());
        result.setRouteCode(proof.getRouteCode());
        result.setDeviceCode(proof.getDeviceCode());
        result.setResumedTime(proof.getResumedTime());
        result.setSourceFingerprint(proof.getPayloadFingerprint());
        result.setMatchStatus(issue == null ? "NO_PLAN" : sourceCase == null ? "NO_RECOVERY_CASE" :
                "KEEP_PUBLISHED".equals(sourceCase.getRecoveryReviewStatus())
                        || "REPLAN_CONFIRMED".equals(sourceCase.getRecoveryReviewStatus())
                        ? "CASE_RESOLVED" : "CASE_PENDING");
        result.setPlanScheduleFingerprint(sourceCase == null ? null : sourceCase.getPublishedScheduleFingerprint());
        result.setMesExecutionFingerprint(mes.getExecutionFingerprint());
        result.setObservedTime(LocalDateTime.now());
        require(observations.insert(result) == 1, "PLAN维修真实复工观察账保存失败");
        return result;
    }
}
