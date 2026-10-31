package com.kitex.mom.plan.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.plan.agent.dto.PlanRepairResumeObservationDTO;
import com.kitex.mom.plan.entity.PlnAutomationEventEntity;
import com.kitex.mom.plan.entity.PlnRepairResumeObservation;
import com.kitex.mom.plan.mapper.PlnAutomationEventMapper;
import com.kitex.mom.plan.mapper.PlnRepairResumeObservationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 从 PLAN 原收件箱和观察账重采维修真实复工的处理结果；不触发事件重试或排程复核。
 */
@Service
@RequiredArgsConstructor
public class PlanRepairResumeObservationQueryService {

    private final PlanAgentActualScopeAccess scopeAccess;
    private final PlnAutomationEventMapper events;
    private final PlnRepairResumeObservationMapper observations;
    private final ObjectMapper mapper;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PlanRepairResumeObservationDTO.Evidence query(PlanRepairResumeObservationDTO.Query query) {
        if (query == null || query.getInboxIds() == null || query.getInboxIds().isEmpty()
                || query.getInboxIds().size() > 30 || new HashSet<>(query.getInboxIds()).size() != query.getInboxIds().size()) {
            throw new CheckedException("PLAN复工观察查询须提供1至30个不重复的精确收件主键");
        }
        scopeAccess.require(query.getFactoryCode(), "plan_resourceReplan_view");
        List<Long> ids = new ArrayList<>();
        for (String value : query.getInboxIds()) {
            if (value == null || !value.matches("[1-9][0-9]{0,18}"))
                throw new CheckedException("PLAN收件主键必须是正整数");
            try {
                ids.add(Long.valueOf(value));
            } catch (NumberFormatException overflow) {
                throw new CheckedException("PLAN收件主键超出数据库范围");
            }
        }
        Long tenantId = TenantContextHolder.getTenantId();
        String factory = query.getFactoryCode();
        List<PlnAutomationEventEntity> inboxes = events.selectList(new LambdaQueryWrapper<PlnAutomationEventEntity>()
                .eq(PlnAutomationEventEntity::getTenantId, tenantId)
                .eq(PlnAutomationEventEntity::getFactoryCode, factory)
                .in(PlnAutomationEventEntity::getId, ids));
        if (inboxes.size() != ids.size())
            throw new CheckedException("当前租户及工厂没有全部指定的PLAN复工收件");
        Map<Long, PlnAutomationEventEntity> eventById = new HashMap<>();
        for (PlnAutomationEventEntity event : inboxes) {
            if (!"MES".equals(event.getSourceSystem()) || !"MES_REPAIR_RESUMED".equals(event.getEventType())
                    || !"MES_REPAIR_RESUME".equals(event.getAggregateType())
                    || !"MES_REPAIR_RESUME_OBSERVE".equals(event.getHandlerCode()))
                throw new CheckedException("指定PLAN收件并非维修真实复工观察事件");
            eventById.put(event.getId(), event);
        }
        List<PlnRepairResumeObservation> rows = observations.selectList(
                new LambdaQueryWrapper<PlnRepairResumeObservation>()
                        .eq(PlnRepairResumeObservation::getTenantId, tenantId)
                        .eq(PlnRepairResumeObservation::getFactoryCode, factory)
                        .in(PlnRepairResumeObservation::getInboxId, ids));
        Map<Long, PlnRepairResumeObservation> observationByInbox = new HashMap<>();
        for (PlnRepairResumeObservation row : rows) {
            if (observationByInbox.put(row.getInboxId(), row) != null)
                throw new CheckedException("PLAN同一收件出现多条维修复工观察账");
        }
        var result = new PlanRepairResumeObservationDTO.Evidence()
                .setFactoryCode(factory).setObservedAt(LocalDateTime.now())
                .setRequestedCount(ids.size()).setProcessedCount(0).setVerifiedObservationCount(0);
        for (Long id : ids) {
            PlnAutomationEventEntity event = eventById.get(id);
            PlnRepairResumeObservation row = observationByInbox.get(id);
            var item = new PlanRepairResumeObservationDTO.Item()
                    .setInboxId(id.toString()).setEventId(event.getEventId())
                    .setEventStatus(event.getStatus()).setObservationVerified(false);
            result.getItems().add(item);
            result.getEvidenceRefs().add("PLAN:pln_automation_event:" + id);
            if ("SUCCEEDED".equals(event.getStatus())) {
                result.setProcessedCount(result.getProcessedCount() + 1);
                if (!validObservation(event, row)) {
                    result.getDataGaps().add("PLAN收件" + id + "已处理但正式维修复工观察账不一致");
                    continue;
                }
                item.setObservationVerified(true).setObservationId(row.getId().toString())
                        .setMesOutboxId(row.getMesOutboxId().toString())
                        .setStopId(row.getStopId().toString())
                        .setSourceFingerprint(row.getSourceFingerprint())
                        .setPlanNo(row.getPlanNo()).setMatchStatus(row.getMatchStatus())
                        .setResourceCaseId(row.getResourceCaseId() == null ? null : row.getResourceCaseId().toString())
                        .setObservedTime(row.getObservedTime());
                result.setVerifiedObservationCount(result.getVerifiedObservationCount() + 1);
                result.getEvidenceRefs().add("PLAN:pln_repair_resume_observation:" + row.getId());
            } else if (row != null) {
                result.getDataGaps().add("PLAN收件" + id + "未成功处理却已有维修复工观察账");
            }
        }
        result.setFactFingerprint(fingerprint(result));
        return result;
    }

    /**
     * 成功事件与观察账应在原处理事务中一同落账，并与 MES 事件身份一致。
     */
    private boolean validObservation(PlnAutomationEventEntity event, PlnRepairResumeObservation row) {
        return row != null && Objects.equals(row.getInboxId(), event.getId())
                && Objects.equals(event.getTargetType(), "REPAIR_RESUME_OBSERVATION")
                && Objects.equals(event.getTargetId(), row.getId())
                && Objects.equals(event.getTargetNo(), String.valueOf(row.getMesOutboxId()))
                && row.getMesOutboxId() != null && row.getStopId() != null && row.getSegmentId() != null
                && Objects.equals(event.getEventId(), "MES-REPAIR-RESUME:" + row.getSegmentId())
                && row.getSourceFingerprint() != null && row.getSourceFingerprint().matches("[0-9a-f]{64}")
                && row.getObservedTime() != null && row.getResumedTime() != null
                && row.getPlanNo() != null && !row.getPlanNo().isBlank()
                && row.getMatchStatus() != null
                && List.of("NO_PLAN", "NO_RECOVERY_CASE", "CASE_PENDING", "CASE_RESOLVED")
                .contains(row.getMatchStatus());
    }

    private String fingerprint(PlanRepairResumeObservationDTO.Evidence evidence) {
        try {
            String facts = mapper.writeValueAsString(Arrays.asList(evidence.getFactoryCode(),
                    evidence.getRequestedCount(), evidence.getProcessedCount(),
                    evidence.getVerifiedObservationCount(), evidence.getItems(), evidence.getDataGaps()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(facts.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception invalid) {
            throw new CheckedException("PLAN维修复工观察事实无法生成完整指纹");
        }
    }
}
