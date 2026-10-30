package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.plan.dto.PlanAutomationEventDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.PlnAutomationEventMapper;
import com.kitex.mom.plan.mapper.PlnAutomationEventOperationMapper;
import com.kitex.mom.plan.mapper.PlnAutomationEventPolicyMapper;
import com.kitex.mom.plan.service.PlnAutomationEventService;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PlnAutomationEventServiceImpl implements PlnAutomationEventService {
    static final Set<String> SUPPORTED_EVENT_TYPES =
            Set.of(
                    "PLAN_MRP_READY",
                    "MES_QUALITY_CALLBACK",
                    "QMS_QUALITY_CHANGED",
                    "RESOURCE_UNAVAILABLE",
                    "MATERIAL_SHORTAGE_CHANGED",
                    "URGENT_INSERTION_REQUESTED",
                    "RESOURCE_AVAILABLE",
                    "MES_REPAIR_RESUMED",
                    "MES_COMPLETION_REOPENED");
    private static final Set<String> FINAL_STATUSES = Set.of("SUCCEEDED", "IGNORED");

    private final PlnAutomationEventMapper eventMapper;
    private final PlnAutomationEventPolicyMapper policyMapper;
    private final PlnAutomationEventOperationMapper operationMapper;
    private final ObjectMapper objectMapper;

    static String defaultHandler(String eventType) {
        return switch (eventType) {
            case "PLAN_MRP_READY" -> "APS_SIMULATE";
            case "MES_QUALITY_CALLBACK" -> "QUALITY_EVALUATE";
            case "QMS_QUALITY_CHANGED" -> "QUALITY_SOURCE_RECONCILE";
            case "RESOURCE_UNAVAILABLE" -> "RESOURCE_LOCAL_REPLAN_ASSESS";
            case "RESOURCE_AVAILABLE" -> "RESOURCE_RECOVERY_REVIEW";
            case "MES_REPAIR_RESUMED" -> "MES_REPAIR_RESUME_OBSERVE";
            case "MES_COMPLETION_REOPENED" -> "MES_COMPLETION_REOPEN_APPLY";
            case "MATERIAL_SHORTAGE_CHANGED" -> "SHORTAGE_LOCAL_REPLAN_PROPOSE";
            case "URGENT_INSERTION_REQUESTED" -> "INSERTION_LOCAL_REPLAN_ASSESS";
            default -> throw new CheckedException("未注册的制造事件类型");
        };
    }

    private static String trim(String value) {
        return StrUtil.isBlank(value) ? null : value.trim();
    }

    private static int bounded(Integer value, int fallback, int min, int max, String message) {
        int result = Objects.requireNonNullElse(value, fallback);
        require(result >= min && result <= max, message);
        return result;
    }

    private static boolean intervalsOverlap(LocalDateTime aStart, LocalDateTime aEnd, LocalDateTime bStart, LocalDateTime bEnd) {
        return (aEnd == null || bStart.isBefore(aEnd)) && (bEnd == null || aStart.isBefore(bEnd));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanAutomationEventDTO.ReceiveReceipt receive(PlanAutomationEventDTO.ReceiveCommand command) {
        validateReceive(command);
        String eventId = command.getEventId().trim();
        String source = command.getSourceSystem().trim().toUpperCase(Locale.ROOT);
        String eventType = command.getEventType().trim().toUpperCase(Locale.ROOT);
        String factoryCode = StrUtil.blankToDefault(command.getFactoryCode(), FactoryContextHolder.getFactoryCode());
        require(StrUtil.isNotBlank(factoryCode), "事件工厂不能为空");
        requireFactory(factoryCode);
        String normalizedPayload = receivePayload(command, source, eventType);
        String requestFingerprint =
                hash(
                        String.join(
                                "|",
                                source,
                                eventId,
                                eventType,
                                String.valueOf(
                                        Objects.requireNonNullElse(command.getEventVersion(), 1)),
                                StrUtil.blankToDefault(command.getAggregateType(), ""),
                                StrUtil.blankToDefault(command.getAggregateId(), ""),
                                factoryCode,
                                normalizedPayload));
        eventMapper.lockKey(source + ':' + eventId);
        PlnAutomationEventEntity existing = eventMapper.selectOne(new LambdaQueryWrapper<PlnAutomationEventEntity>()
                .eq(PlnAutomationEventEntity::getSourceSystem, source)
                .eq(PlnAutomationEventEntity::getEventId, eventId));
        if (existing != null) {
            require(Objects.equals(existing.getRequestFingerprint(), requestFingerprint),
                    "同一来源事件ID的事实内容不一致，已拒绝覆盖原始证据");
            return receipt(existing, true);
        }
        PlnAutomationEventPolicyEntity policy = resolvePolicy(factoryCode, eventType, LocalDateTime.now());
        String handler = policy == null ? defaultHandler(eventType) : policy.getHandlerCode();
        boolean autoExecute = policy == null || Boolean.TRUE.equals(policy.getAutoExecute());
        int maxAttempts = policy == null ? 5 : policy.getMaxAttempts();
        LocalDateTime now = LocalDateTime.now();
        PlnAutomationEventEntity event = new PlnAutomationEventEntity()
                .setEventNo("AEV-" + IdUtil.getSnowflakeNextIdStr()).setEventId(eventId)
                .setSourceSystem(source).setEventType(eventType)
                .setEventVersion(Objects.requireNonNullElse(command.getEventVersion(), 1))
                .setAggregateType(trim(command.getAggregateType())).setAggregateId(trim(command.getAggregateId()))
                .setRequestFingerprint(requestFingerprint).setPayloadJson(normalizedPayload)
                .setPayloadFingerprint(hash(normalizedPayload))
                .setOccurredTime(Objects.requireNonNullElse(command.getOccurredTime(), now)).setReceivedTime(now)
                .setPriority(bounded(command.getPriority(), 100, 0, 9999, "事件优先级必须在0到9999之间"))
                .setPolicyId(policy == null ? null : policy.getId()).setPolicyCode(policy == null ? null : policy.getPolicyCode())
                .setPolicyVersion(policy == null ? null : policy.getPolicyVersion())
                .setPolicyFingerprint(policy == null ? null : policy.getPolicyFingerprint())
                .setHandlerCode(handler).setAutoExecute(autoExecute)
                .setStatus(autoExecute ? "RECEIVED" : "WAITING_MANUAL").setAttemptCount(0)
                .setMaxAttempts(maxAttempts).setNextAttemptTime(autoExecute ? now : null).setFactoryCode(factoryCode);
        require(eventMapper.insert(event) == 1, "制造异常事件收件失败");
        operation(event, "RECEIVE:" + source + ':' + eventId, "RECEIVE", null, event.getStatus(), 0,
                policy == null ? "按内置安全路由接收" : "按订阅策略" + policy.getPolicyCode() + "接收");
        return receipt(event, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanAutomationEventDTO.ReceiveReceipt receiveQualityCallback(PlnIssueCallbackReceiptEntity receipt,
                                                                        PlnIssueEntity issue) {
        require(receipt != null && receipt.getId() != null && issue != null, "质量回执事件参数不完整");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("receiptId", receipt.getId());
        payload.put("issueId", issue.getId());
        payload.put("issueNo", issue.getIssueNo());
        payload.put("sourceRequestId", receipt.getRequestId());
        PlanAutomationEventDTO.ReceiveCommand command = new PlanAutomationEventDTO.ReceiveCommand();
        command.setEventId("MES-CALLBACK:" + receipt.getRequestId());
        command.setSourceSystem("MES");
        command.setEventType("MES_QUALITY_CALLBACK");
        command.setEventVersion(1);
        command.setAggregateType("PLN_ISSUE");
        command.setAggregateId(String.valueOf(issue.getId()));
        command.setFactoryCode(issue.getFactoryCode());
        command.setOccurredTime(receipt.getReceivedTime());
        command.setPriority(20);
        command.setPayloadJson(json(payload));
        return receive(command);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanAutomationEventDTO.ReceiveReceipt receiveMrpReady(String factoryCode, String aggregateId,
                                                                 String cycleType, LocalDate scheduleDate, String scene) {
        require(StrUtil.isNotBlank(aggregateId) && scheduleDate != null, "MRP就绪事件参数不完整");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cycleType", StrUtil.blankToDefault(cycleType, "DAY"));
        payload.put("scheduleDate", scheduleDate.toString());
        payload.put("scene", scene);
        PlanAutomationEventDTO.ReceiveCommand command = new PlanAutomationEventDTO.ReceiveCommand();
        command.setEventId("MRP-READY:" + aggregateId + ':' + scheduleDate);
        command.setSourceSystem("PLAN");
        command.setEventType("PLAN_MRP_READY");
        command.setEventVersion(1);
        command.setAggregateType("PLAN_DEMAND");
        command.setAggregateId(aggregateId);
        command.setFactoryCode(factoryCode);
        command.setOccurredTime(LocalDateTime.now());
        command.setPriority(50);
        command.setPayloadJson(json(payload));
        return receive(command);
    }

    @Override
    public Page<PlnAutomationEventEntity> eventPage(SearchPage page, PlanAutomationEventDTO.EventQuery query) {
        PlanAutomationEventDTO.EventQuery q = query == null ? new PlanAutomationEventDTO.EventQuery() : query;
        return eventMapper.selectPage(page.toMpPage(), new LambdaQueryWrapper<PlnAutomationEventEntity>()
                .like(StrUtil.isNotBlank(q.getEventNo()), PlnAutomationEventEntity::getEventNo, q.getEventNo())
                .eq(StrUtil.isNotBlank(q.getSourceSystem()), PlnAutomationEventEntity::getSourceSystem, q.getSourceSystem())
                .eq(StrUtil.isNotBlank(q.getEventType()), PlnAutomationEventEntity::getEventType, q.getEventType())
                .eq(StrUtil.isNotBlank(q.getAggregateId()), PlnAutomationEventEntity::getAggregateId, q.getAggregateId())
                .eq(StrUtil.isNotBlank(q.getStatus()), PlnAutomationEventEntity::getStatus, q.getStatus())
                .eq(StrUtil.isNotBlank(q.getHandlerCode()), PlnAutomationEventEntity::getHandlerCode, q.getHandlerCode())
                .orderByAsc(PlnAutomationEventEntity::getPriority).orderByDesc(PlnAutomationEventEntity::getReceivedTime));
    }

    @Override
    public PlanAutomationEventDTO.EventDetail detail(Long eventInboxId) {
        PlnAutomationEventEntity event = requireEvent(eventInboxId, false);
        PlanAutomationEventDTO.EventDetail result = new PlanAutomationEventDTO.EventDetail();
        BeanUtils.copyProperties(event, result);
        if (event.getPolicyId() != null) result.setPolicy(policyMapper.selectById(event.getPolicyId()));
        result.setOperations(operationMapper.selectList(new LambdaQueryWrapper<PlnAutomationEventOperationEntity>()
                .eq(PlnAutomationEventOperationEntity::getEventId, eventInboxId)
                .orderByAsc(PlnAutomationEventOperationEntity::getOperationTime)
                .orderByAsc(PlnAutomationEventOperationEntity::getId)));
        return result;
    }

    @Override
    public Page<PlnAutomationEventPolicyEntity> policyPage(SearchPage page, PlanAutomationEventDTO.PolicyQuery query) {
        PlanAutomationEventDTO.PolicyQuery q = query == null ? new PlanAutomationEventDTO.PolicyQuery() : query;
        return policyMapper.selectPage(page.toMpPage(), new LambdaQueryWrapper<PlnAutomationEventPolicyEntity>()
                .like(StrUtil.isNotBlank(q.getPolicyCode()), PlnAutomationEventPolicyEntity::getPolicyCode, q.getPolicyCode())
                .eq(StrUtil.isNotBlank(q.getEventType()), PlnAutomationEventPolicyEntity::getEventType, q.getEventType())
                .eq(StrUtil.isNotBlank(q.getHandlerCode()), PlnAutomationEventPolicyEntity::getHandlerCode, q.getHandlerCode())
                .eq(StrUtil.isNotBlank(q.getStatus()), PlnAutomationEventPolicyEntity::getStatus, q.getStatus())
                .orderByDesc(PlnAutomationEventPolicyEntity::getCreateTime));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnAutomationEventPolicyEntity savePolicyDraft(PlanAutomationEventDTO.PolicyDraftCommand command) {
        require(command != null && StrUtil.isNotBlank(command.getPolicyCode()) && StrUtil.isNotBlank(command.getPolicyName())
                && StrUtil.isNotBlank(command.getEventType()), "订阅策略编码、名称和事件类型不能为空");
        String factoryCode = StrUtil.blankToDefault(command.getFactoryCode(), FactoryContextHolder.getFactoryCode());
        require(StrUtil.isNotBlank(factoryCode), "当前工厂不能为空");
        requireFactory(factoryCode);
        String eventType = command.getEventType().trim().toUpperCase(Locale.ROOT);
        require(SUPPORTED_EVENT_TYPES.contains(eventType), "不支持的制造事件类型");
        String handler = StrUtil.blankToDefault(command.getHandlerCode(), defaultHandler(eventType)).trim().toUpperCase(Locale.ROOT);
        require(Objects.equals(handler, defaultHandler(eventType)), "事件类型与处理器不匹配");
        require(command.getEffectiveFrom() == null || command.getEffectiveTo() == null
                || command.getEffectiveTo().isAfter(command.getEffectiveFrom()), "策略失效时间必须晚于生效时间");
        String code = command.getPolicyCode().trim();
        policyMapper.lockKey(factoryCode + ':' + eventType + ':' + code);
        MomUser user = currentUser();
        PlnAutomationEventPolicyEntity entity;
        if (command.getId() == null) {
            int version =
                    policyMapper
                            .selectList(
                                    new LambdaQueryWrapper<PlnAutomationEventPolicyEntity>()
                                            .eq(
                                                    PlnAutomationEventPolicyEntity
                                                            ::getFactoryCode,
                                                    factoryCode)
                                            .eq(
                                                    PlnAutomationEventPolicyEntity
                                                            ::getPolicyCode,
                                                    code)
                                            .orderByDesc(
                                                    PlnAutomationEventPolicyEntity
                                                            ::getPolicyVersion)
                                            .last("LIMIT 1"))
                            .stream()
                            .findFirst()
                            .map(PlnAutomationEventPolicyEntity::getPolicyVersion)
                            .orElse(0)
                            + 1;
            entity = new PlnAutomationEventPolicyEntity().setFactoryCode(factoryCode).setPolicyVersion(version)
                    .setStatus("DRAFT").setAuthorCode(userCode(user)).setAuthorName(userName(user));
        } else {
            entity = requirePolicy(command.getId(), true);
            require("DRAFT".equals(entity.getStatus()), "仅草稿订阅策略允许修改");
            require(Objects.equals(factoryCode, entity.getFactoryCode()), "订阅策略不属于当前工厂");
        }
        entity.setPolicyCode(code)
                .setPolicyName(command.getPolicyName().trim())
                .setEventType(eventType)
                .setHandlerCode(handler)
                .setAutoExecute(Objects.requireNonNullElse(command.getAutoExecute(), true))
                .setMaxAttempts(bounded(command.getMaxAttempts(), 5, 1, 20, "最大尝试次数必须在1到20之间"))
                .setInitialBackoffSeconds(
                        bounded(
                                command.getInitialBackoffSeconds(),
                                60,
                                10,
                                86400,
                                "首次退避必须在10秒到1天之间"))
                .setMaxBackoffSeconds(
                        bounded(
                                command.getMaxBackoffSeconds(),
                                3600,
                                60,
                                604800,
                                "最大退避必须在1分钟到7天之间"))
                .setPriority(bounded(command.getPriority(), 100, 0, 9999, "策略优先级必须在0到9999之间"))
                .setEffectiveFrom(command.getEffectiveFrom())
                .setEffectiveTo(command.getEffectiveTo());
        require(entity.getMaxBackoffSeconds() >= entity.getInitialBackoffSeconds(), "最大退避不能小于首次退避");
        entity.setPolicyFingerprint(policyFingerprint(entity));
        require(entity.getId() == null ? policyMapper.insert(entity) == 1 : policyMapper.updateById(entity) == 1,
                "事件订阅策略草稿保存失败");
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnAutomationEventPolicyEntity activatePolicy(PlanAutomationEventDTO.PolicyActionCommand command) {
        require(
                command != null
                        && command.getPolicyId() != null
                        && StrUtil.isNotBlank(command.getRequestId()),
                "订阅策略启用参数不完整");
        String requestId = command.getRequestId().trim();
        policyMapper.lockKey("ACTIVATE:" + requestId);
        PlnAutomationEventPolicyEntity duplicate = policyMapper.selectOne(new LambdaQueryWrapper<PlnAutomationEventPolicyEntity>()
                .eq(PlnAutomationEventPolicyEntity::getActivationRequestId, requestId));
        if (duplicate != null) {
            require(Objects.equals(duplicate.getId(), command.getPolicyId()), "策略启用幂等号冲突");
            return duplicate;
        }
        PlnAutomationEventPolicyEntity entity = requirePolicy(command.getPolicyId(), true);
        require("DRAFT".equals(entity.getStatus()), "仅草稿订阅策略允许启用");
        MomUser user = currentUser();
        require(!Objects.equals(entity.getAuthorCode(), userCode(user)), "订阅策略编制人与启用复核人必须分离");
        LocalDateTime from = Objects.requireNonNullElse(entity.getEffectiveFrom(), LocalDateTime.now());
        boolean overlap = policyMapper.selectList(new LambdaQueryWrapper<PlnAutomationEventPolicyEntity>()
                        .eq(PlnAutomationEventPolicyEntity::getFactoryCode, entity.getFactoryCode())
                        .eq(PlnAutomationEventPolicyEntity::getEventType, entity.getEventType())
                        .eq(PlnAutomationEventPolicyEntity::getStatus, "ACTIVE"))
                .stream().anyMatch(p -> intervalsOverlap(from, entity.getEffectiveTo(),
                        Objects.requireNonNullElse(p.getEffectiveFrom(), LocalDateTime.MIN), p.getEffectiveTo()));
        require(!overlap, "同工厂同事件类型已有生效期重叠的订阅策略");
        entity.setEffectiveFrom(from).setStatus("ACTIVE").setActivationRequestId(requestId)
                .setApproverCode(userCode(user)).setApproverName(userName(user)).setApprovedTime(LocalDateTime.now());
        entity.setPolicyFingerprint(policyFingerprint(entity));
        require(policyMapper.updateById(entity) == 1, "事件订阅策略启用失败");
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnAutomationEventPolicyEntity retirePolicy(PlanAutomationEventDTO.PolicyActionCommand command) {
        require(command != null && command.getPolicyId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()), "订阅策略退役参数不完整");
        String requestId = command.getRequestId().trim();
        policyMapper.lockKey("RETIRE:" + requestId);
        PlnAutomationEventPolicyEntity duplicate = policyMapper.selectOne(new LambdaQueryWrapper<PlnAutomationEventPolicyEntity>()
                .eq(PlnAutomationEventPolicyEntity::getRetireRequestId, requestId));
        if (duplicate != null) {
            require(Objects.equals(duplicate.getId(), command.getPolicyId()), "策略退役幂等号冲突");
            return duplicate;
        }
        PlnAutomationEventPolicyEntity entity = requirePolicy(command.getPolicyId(), true);
        require("ACTIVE".equals(entity.getStatus()), "仅启用订阅策略允许退役");
        MomUser user = currentUser();
        entity.setStatus("RETIRED")
                .setRetireRequestId(requestId)
                .setRetireReason(command.getReason().trim())
                .setRetiredBy(userCode(user))
                .setRetiredTime(LocalDateTime.now());
        require(policyMapper.updateById(entity) == 1, "事件订阅策略退役失败");
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean retry(PlanAutomationEventDTO.ManualActionCommand command) {
        validateManual(command);
        PlnAutomationEventEntity event = eventMapper.lockById(command.getEventInboxId());
        require(event != null, "事件不存在");
        requireFactory(event.getFactoryCode());
        if (isManualReplay(event, command, "MANUAL_RETRY")) return true;
        require(
                Set.of("WAITING_MANUAL", "DEAD_LETTER", "RETRY_WAIT").contains(event.getStatus()),
                "当前事件状态不允许人工重试");
        String before = event.getStatus();
        int previousAttempts = event.getAttemptCount();
        MomUser user = currentUser();
        event.setStatus("RECEIVED")
                .setNextAttemptTime(LocalDateTime.now())
                .setLastErrorCode(null)
                .setLastErrorMessage(null)
                .setAttemptCount(0)
                .setCompletedTime(null)
                .setManualRequestId(command.getRequestId().trim())
                .setManualReason(command.getReason().trim())
                .setManualOperatorCode(userCode(user))
                .setManualOperatorName(userName(user))
                .setManualOperationTime(LocalDateTime.now());
        require(eventMapper.updateById(event) == 1, "事件人工重试状态保存失败");
        operation(
                event,
                command.getRequestId().trim(),
                "MANUAL_RETRY",
                before,
                "RECEIVED",
                previousAttempts,
                command.getReason().trim());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean ignore(PlanAutomationEventDTO.ManualActionCommand command) {
        validateManual(command);
        PlnAutomationEventEntity event = eventMapper.lockById(command.getEventInboxId());
        require(event != null, "事件不存在");
        requireFactory(event.getFactoryCode());
        if (isManualReplay(event, command, "MANUAL_IGNORE")) return true;
        require(
                !FINAL_STATUSES.contains(event.getStatus())
                        && !"PROCESSING".equals(event.getStatus()),
                "当前事件状态不允许忽略");
        String before = event.getStatus();
        MomUser user = currentUser();
        event.setStatus("IGNORED").setNextAttemptTime(null).setCompletedTime(LocalDateTime.now())
                .setManualRequestId(command.getRequestId().trim()).setManualReason(command.getReason().trim())
                .setManualOperatorCode(userCode(user)).setManualOperatorName(userName(user)).setManualOperationTime(LocalDateTime.now());
        require(eventMapper.updateById(event) == 1, "事件忽略状态保存失败");
        operation(
                event,
                command.getRequestId().trim(),
                "MANUAL_IGNORE",
                before,
                "IGNORED",
                event.getAttemptCount(),
                command.getReason().trim());
        return true;
    }

    private PlnAutomationEventPolicyEntity resolvePolicy(String factoryCode, String eventType, LocalDateTime now) {
        return policyMapper
                .selectList(
                        new LambdaQueryWrapper<PlnAutomationEventPolicyEntity>()
                                .eq(PlnAutomationEventPolicyEntity::getFactoryCode, factoryCode)
                                .eq(PlnAutomationEventPolicyEntity::getEventType, eventType)
                                .eq(PlnAutomationEventPolicyEntity::getStatus, "ACTIVE")
                                .and(
                                        w ->
                                                w.isNull(
                                                                PlnAutomationEventPolicyEntity
                                                                        ::getEffectiveFrom)
                                                        .or()
                                                        .le(
                                                                PlnAutomationEventPolicyEntity
                                                                        ::getEffectiveFrom,
                                                                now))
                                .and(
                                        w ->
                                                w.isNull(
                                                                PlnAutomationEventPolicyEntity
                                                                        ::getEffectiveTo)
                                                        .or()
                                                        .gt(
                                                                PlnAutomationEventPolicyEntity
                                                                        ::getEffectiveTo,
                                                                now))
                                .orderByAsc(PlnAutomationEventPolicyEntity::getPriority)
                                .orderByDesc(PlnAutomationEventPolicyEntity::getPolicyVersion)
                                .last("LIMIT 1"))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private void validateReceive(PlanAutomationEventDTO.ReceiveCommand c) {
        require(
                c != null
                        && StrUtil.isNotBlank(c.getEventId())
                        && StrUtil.isNotBlank(c.getSourceSystem())
                        && StrUtil.isNotBlank(c.getEventType())
                        && StrUtil.isNotBlank(c.getPayloadJson()),
                "事件ID、来源、类型和事实载荷不能为空");
        require(
                c.getEventId().trim().length() <= 160 && c.getSourceSystem().trim().length() <= 40,
                "事件标识长度不合法");
        require(
                SUPPORTED_EVENT_TYPES.contains(c.getEventType().trim().toUpperCase(Locale.ROOT)),
                "不支持的制造事件类型");
        require(c.getEventVersion() == null || c.getEventVersion() > 0, "事件版本必须大于0");
    }

    private void validateManual(PlanAutomationEventDTO.ManualActionCommand c) {
        require(c != null && c.getEventInboxId() != null && StrUtil.isNotBlank(c.getRequestId())
                && StrUtil.isNotBlank(c.getReason()), "事件人工处置参数不完整");
    }

    private boolean isManualReplay(PlnAutomationEventEntity event, PlanAutomationEventDTO.ManualActionCommand command,
                                   String expectedType) {
        PlnAutomationEventOperationEntity duplicate = operationMapper.selectOne(
                new LambdaQueryWrapper<PlnAutomationEventOperationEntity>()
                        .eq(PlnAutomationEventOperationEntity::getRequestId, command.getRequestId().trim()));
        if (duplicate == null) return false;
        require(Objects.equals(duplicate.getEventId(), event.getId())
                        && Objects.equals(duplicate.getOperationType(), expectedType)
                        && Objects.equals(duplicate.getContent(), command.getReason().trim()),
                "人工处置幂等号已被不同动作、事件或内容使用");
        return true;
    }

    private PlnAutomationEventEntity requireEvent(Long id, boolean lock) {
        require(id != null, "事件ID不能为空");
        PlnAutomationEventEntity event = lock ? eventMapper.lockById(id) : eventMapper.selectById(id);
        require(event != null, "事件不存在");
        requireFactory(event.getFactoryCode());
        return event;
    }

    private PlnAutomationEventPolicyEntity requirePolicy(Long id, boolean lock) {
        require(id != null, "订阅策略ID不能为空");
        if (lock) policyMapper.lockKey("ID:" + id);
        PlnAutomationEventPolicyEntity entity = policyMapper.selectById(id);
        require(entity != null, "订阅策略不存在");
        requireFactory(entity.getFactoryCode());
        return entity;
    }

    private void operation(PlnAutomationEventEntity event, String requestId, String type, String before,
                           String after, Integer attempt, String content) {
        PlnAutomationEventOperationEntity op = new PlnAutomationEventOperationEntity().setEventId(event.getId())
                .setRequestId(requestId).setOperationType(type).setBeforeStatus(before).setAfterStatus(after)
                .setAttemptNo(Objects.requireNonNullElse(attempt, 0)).setContent(content)
                .setOperatorCode(userCode(currentUser())).setOperatorName(userName(currentUser()))
                .setOperationTime(LocalDateTime.now()).setFactoryCode(event.getFactoryCode());
        require(operationMapper.insert(op) == 1, "事件操作履历保存失败");
    }

    private PlanAutomationEventDTO.ReceiveReceipt receipt(
            PlnAutomationEventEntity event, boolean duplicate) {
        return new PlanAutomationEventDTO.ReceiveReceipt()
                .setInboxId(event.getId())
                .setEventNo(event.getEventNo())
                .setEventId(event.getEventId())
                .setStatus(event.getStatus())
                .setDuplicate(duplicate)
                .setReceivedTime(event.getReceivedTime());
    }

    /**
     * 新反向事件传递MES签名原件；历史事件仍沿用原规范化和幂等摘要。
     */
    private String receivePayload(
            PlanAutomationEventDTO.ReceiveCommand command, String source, String eventType) {
        if (!"MES".equals(source)
                || !"MES_COMPLETION_REOPENED".equals(eventType)
                || !Objects.equals(command.getEventVersion(), 2))
            return normalizeJson(command.getPayloadJson());
        String raw = command.getPayloadJson();
        require(raw != null && raw.length() <= 1_000_000, "反向完成原件范围无效");
        try {
            var node =
                    objectMapper
                            .copy()
                            .enable(
                                    com.fasterxml.jackson.core.JsonParser.Feature
                                            .STRICT_DUPLICATE_DETECTION)
                            .readTree(raw);
            require(
                    node != null
                            && node.isObject()
                            && node.path("sourceSchemaVersion").isIntegralNumber()
                            && node.path("sourceSchemaVersion").intValue() == 2,
                    "反向完成原件模型与信封不符");
            return raw;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new CheckedException("反向完成原件不是有效JSON对象");
        }
    }

    private String normalizeJson(String value) {
        try {
            return objectMapper.writeValueAsString(objectMapper.readTree(value));
        } catch (Exception ex) {
            throw new CheckedException("事件事实载荷必须是合法JSON");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new CheckedException("事件事实载荷序列化失败");
        }
    }

    private String policyFingerprint(PlnAutomationEventPolicyEntity p) {
        return hash(
                String.join(
                        "|",
                        p.getFactoryCode(),
                        p.getPolicyCode(),
                        String.valueOf(p.getPolicyVersion()),
                        p.getEventType(),
                        p.getHandlerCode(),
                        String.valueOf(p.getAutoExecute()),
                        String.valueOf(p.getMaxAttempts()),
                        String.valueOf(p.getInitialBackoffSeconds()),
                        String.valueOf(p.getMaxBackoffSeconds()),
                        String.valueOf(p.getPriority()),
                        String.valueOf(p.getEffectiveFrom()),
                        String.valueOf(p.getEffectiveTo())));
    }

    private String hash(String raw) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256不可用", ex);
        }
    }

    private MomUser currentUser() {
        try {
            return SecurityUtils.getUser();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void requireFactory(String factoryCode) {
        String current = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(current) || Objects.equals(current, factoryCode), "请求工厂超出当前数据范围");
    }

    private String userCode(MomUser u) {
        return u == null ? "SYSTEM" : u.getUsername();
    }

    private String userName(MomUser u) {
        return u == null ? "系统" : StrUtil.blankToDefault(u.getName(), u.getUsername());
    }
}
