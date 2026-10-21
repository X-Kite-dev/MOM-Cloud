package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmPlanResourceEventDTO;
import com.kitex.mom.mdm.entity.*;
import com.kitex.mom.mdm.mapper.MdmPlanResourceEventOperationMapper;
import com.kitex.mom.mdm.mapper.MdmPlanResourceEventOutboxMapper;
import com.kitex.mom.mdm.service.MdmPlanResourceEventOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MdmPlanResourceEventOutboxServiceImpl implements MdmPlanResourceEventOutboxService {
    public static final String TOOLING_WINDOW = "MDM_TOOLING_UNAVAILABLE_WINDOW";
    public static final String TOOLING_MASTER = "MDM_TOOLING_MASTER_STATUS";
    public static final String EMPLOYEE_CALENDAR = "MDM_EMPLOYEE_CALENDAR_EXCEPTION";
    public static final String EMPLOYEE_QUALIFICATION = "MDM_EMPLOYEE_QUALIFICATION_INVALID";
    public static final String DEVICE_RECOVERY = "MDM_DEVICE_RECOVERY";
    private static final Set<String> SCHEDULABLE_TOOLING_STATES = Set.of("AVAILABLE", "IN_USE");

    private final MdmPlanResourceEventOutboxMapper outboxMapper;
    private final MdmPlanResourceEventOperationMapper operationMapper;
    private final MdmPlanResourceEventOutboxWorker worker;
    private final MdmPlanResourceEventOutboxFailureService failureService;
    private final ObjectMapper objectMapper;

    private static String compact(String value, int max) {
        String text = StrUtil.blankToDefault(value, "-").trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MdmPlanResourceEventOutboxEntity enqueueDeviceRecovery(MdmDeviceRecoveryReceipt receipt, MdmDeviceEntity device,
                                                                  Long taskId, String taskCode) {
        require(receipt != null && receipt.getId() != null && device != null && device.getId() != null && taskId != null
                && StrUtil.isNotBlank(taskCode) && Objects.equals(receipt.getDeviceId(), device.getId())
                && Objects.equals(receipt.getTenantId(), device.getTenantId()) && Objects.equals(receipt.getFactoryCode(), device.getFactoryCode())
                && Objects.equals(receipt.getDeviceCode(), device.getDeviceCode()) && Boolean.TRUE.equals(device.getStatus())
                && "0".equals(device.getDeviceState()) && "1".equals(device.getDeviceUsable()), "MDM恢复PLAN事件必须关联本事务真实资格恢复回执和空闲可用设备");
        String eventId = "MDM-DEVICE-RECOVERY:" + receipt.getId();
        outboxMapper.lockKey(eventId);
        var existing = outboxMapper.selectOne(new LambdaQueryWrapper<MdmPlanResourceEventOutboxEntity>()
                .eq(MdmPlanResourceEventOutboxEntity::getEventId, eventId));
        if (existing != null) {
            require(DEVICE_RECOVERY.equals(existing.getSourceType()) && Objects.equals(existing.getSourceId(), receipt.getId()),
                    "MDM设备恢复事件ID已用于不同来源");
            return existing;
        }
        LocalDateTime occurred = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        var source = new LinkedHashMap<String, Object>();
        source.put("receiptId", String.valueOf(receipt.getId()));
        source.put("verificationId", String.valueOf(receipt.getVerificationId()));
        source.put("taskId", String.valueOf(taskId));
        source.put("taskCode", taskCode);
        source.put("deviceId", String.valueOf(device.getId()));
        source.put("deviceCode", device.getDeviceCode());
        source.put("requestFingerprint", receipt.getRequestFingerprint());
        source.put("sourceFingerprint", receipt.getSourceFingerprint());
        source.put("afterJson", receipt.getAfterJson());
        source.put("recoveredTime", occurred);
        String sourceJson = json(source);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("resourceType", "DEVICE");
        payload.put("resourceCode", device.getDeviceCode());
        payload.put("resourceName", device.getDeviceName());
        payload.put("deviceId", String.valueOf(device.getId()));
        payload.put("recoveryReceiptId", String.valueOf(receipt.getId()));
        payload.put("verificationId", String.valueOf(receipt.getVerificationId()));
        payload.put("taskId", String.valueOf(taskId));
        payload.put("taskCode", taskCode);
        payload.put("requestFingerprint", receipt.getRequestFingerprint());
        payload.put("sourceFingerprint", receipt.getSourceFingerprint());
        payload.put("recoveredTime", occurred);
        payload.put("deviceState", device.getDeviceState());
        payload.put("deviceUsable", device.getDeviceUsable());
        String body = json(payload);
        var outbox = new MdmPlanResourceEventOutboxEntity().setEventId(eventId).setEventType("RESOURCE_AVAILABLE")
                .setEventVersion(1).setSourceType(DEVICE_RECOVERY).setSourceId(receipt.getId()).setSourceNo(receipt.getRequestId())
                .setResourceType("DEVICE").setResourceCode(device.getDeviceCode()).setResourceName(device.getDeviceName())
                .setOccurredTime(occurred).setEventStartTime(occurred).setExpectedRecoveryTime(occurred).setOpenEnded(false)
                .setReason("设备维修独立验证后资格恢复").setSourcePayloadJson(sourceJson).setSourceFingerprint(hash(sourceJson))
                .setPayloadJson(body).setPayloadFingerprint(hash(body)).setStatus("PENDING").setSourceStatus("ACTIVE")
                .setAttemptCount(0).setMaxAttempts(8).setNextAttemptTime(LocalDateTime.now()).setFactoryCode(device.getFactoryCode());
        outbox.setTenantId(receipt.getTenantId());
        require(outboxMapper.insert(outbox) == 1, "MDM设备恢复PLAN事件入箱失败");
        operation(outbox, "CREATE:" + eventId, "OUTBOX_CREATED", null, "PENDING", 0, "设备资格恢复回执与PLAN资源可用事实同事务入箱");
        return outbox;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void synchronizeToolingWindows(MdmToolingEntity tooling,
                                          List<MdmToolingUnavailableWindowEntity> windows) {
        require(tooling != null && tooling.getId() != null && StrUtil.isNotBlank(tooling.getToolingCode())
                && StrUtil.isNotBlank(tooling.getFactoryCode()), "工装不可用事件缺少工装或工厂");
        List<ResourceFact> facts = Optional.ofNullable(windows).orElse(List.of()).stream()
                .filter(item -> item != null && item.getId() != null && !Boolean.FALSE.equals(item.getStatus()))
                .map(item -> toolingFact(tooling, item)).filter(Objects::nonNull).toList();
        synchronize(TOOLING_WINDOW, "TOOLING", tooling.getToolingCode(), tooling.getToolingName(),
                tooling.getFactoryCode(), facts);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void synchronizeToolingMasterStatus(MdmToolingEntity tooling) {
        require(tooling != null && tooling.getId() != null && StrUtil.isNotBlank(tooling.getToolingCode())
                && StrUtil.isNotBlank(tooling.getFactoryCode()), "工装主状态事件缺少工装或工厂");
        String state = StrUtil.blankToDefault(tooling.getToolingState(), "AVAILABLE").toUpperCase(Locale.ROOT);
        boolean unavailable = !Boolean.TRUE.equals(tooling.getStatus()) || !SCHEDULABLE_TOOLING_STATES.contains(state);
        List<ResourceFact> facts = unavailable ? List.of(toolingMasterFact(tooling, state)) : List.of();
        synchronize(TOOLING_MASTER, "TOOLING", tooling.getToolingCode(), tooling.getToolingName(),
                tooling.getFactoryCode(), facts);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void synchronizeEmployeeCalendar(MdmEmployeeEntity employee,
                                            List<MdmEmployeeCalendarExceptionEntity> exceptions) {
        require(employee != null && employee.getId() != null && StrUtil.isNotBlank(employee.getEmployeeCode())
                && StrUtil.isNotBlank(employee.getFactoryCode()), "人员日历事件缺少员工或工厂");
        List<ResourceFact> facts = Optional.ofNullable(exceptions).orElse(List.of()).stream()
                .filter(item -> item != null && item.getId() != null && !Boolean.FALSE.equals(item.getStatus())
                        && !"OVERTIME".equalsIgnoreCase(item.getExceptionType()))
                .map(item -> employeeFact(employee, item)).filter(Objects::nonNull).toList();
        synchronize(EMPLOYEE_CALENDAR, "EMPLOYEE", employee.getEmployeeCode(), employee.getName(),
                employee.getFactoryCode(), facts);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void synchronizeEmployeeQualifications(MdmEmployeeEntity employee,
                                                  List<MdmEmployeeSkillEntity> qualifications) {
        require(employee != null && employee.getId() != null && StrUtil.isNotBlank(employee.getEmployeeCode())
                && StrUtil.isNotBlank(employee.getFactoryCode()), "人员资质事件缺少员工或工厂");
        List<ResourceFact> facts = Optional.ofNullable(qualifications).orElse(List.of()).stream()
                .filter(item -> item != null && item.getId() != null)
                .map(item -> qualificationFact(employee, item)).filter(Objects::nonNull).toList();
        synchronize(EMPLOYEE_QUALIFICATION, "EMPLOYEE", employee.getEmployeeCode(), employee.getName(),
                employee.getFactoryCode(), facts);
    }

    private void synchronize(String sourceType, String resourceType, String resourceCode, String resourceName,
                             String factoryCode, List<ResourceFact> facts) {
        String lockKey = sourceType + ':' + factoryCode + ':' + resourceCode;
        outboxMapper.lockKey(lockKey);
        Set<String> currentEventIds = new HashSet<>();
        for (ResourceFact fact : facts) {
            currentEventIds.add(fact.eventId());
            MdmPlanResourceEventOutboxEntity existing = outboxMapper.selectOne(
                    new LambdaQueryWrapper<MdmPlanResourceEventOutboxEntity>()
                            .eq(MdmPlanResourceEventOutboxEntity::getEventId, fact.eventId()));
            if (existing != null) {
                require(Objects.equals(existing.getSourceFingerprint(), fact.sourceFingerprint())
                                && Objects.equals(existing.getResourceCode(), resourceCode),
                        "同一MDM资源事件ID的权威事实不一致，禁止覆盖");
                continue;
            }
            String payload = payload(sourceType, resourceType, resourceCode, resourceName, fact, factoryCode);
            MdmPlanResourceEventOutboxEntity outbox = new MdmPlanResourceEventOutboxEntity()
                    .setEventId(fact.eventId()).setEventType("RESOURCE_UNAVAILABLE").setEventVersion(1)
                    .setSourceType(sourceType).setSourceId(fact.sourceId()).setSourceNo(fact.sourceNo())
                    .setResourceType(resourceType).setResourceCode(resourceCode).setResourceName(resourceName)
                    .setOccurredTime(LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS))
                    .setEventStartTime(fact.start()).setExpectedRecoveryTime(fact.end())
                    .setOpenEnded(fact.openEnded()).setReason(fact.reason())
                    .setSourcePayloadJson(fact.sourceJson()).setSourceFingerprint(fact.sourceFingerprint())
                    .setPayloadJson(payload).setPayloadFingerprint(hash(payload)).setStatus("PENDING")
                    .setSourceStatus("ACTIVE").setAttemptCount(0).setMaxAttempts(8)
                    .setNextAttemptTime(LocalDateTime.now()).setFactoryCode(factoryCode);
            require(outboxMapper.insert(outbox) == 1, "MDM资源事件Outbox保存失败");
            operation(outbox, "CREATE:" + fact.eventId(), "OUTBOX_CREATED", null, "PENDING", 0,
                    (fact.openEnded() ? "MDM开放式不可用事实" : "MDM不可用窗口")
                            + "与主数据事务同箱，等待投递PLAN");
        }
        List<MdmPlanResourceEventOutboxEntity> active = outboxMapper.selectList(
                new LambdaQueryWrapper<MdmPlanResourceEventOutboxEntity>()
                        .eq(MdmPlanResourceEventOutboxEntity::getSourceType, sourceType)
                        .eq(MdmPlanResourceEventOutboxEntity::getFactoryCode, factoryCode)
                        .eq(MdmPlanResourceEventOutboxEntity::getResourceCode, resourceCode)
                        .eq(MdmPlanResourceEventOutboxEntity::getSourceStatus, "ACTIVE"));
        for (MdmPlanResourceEventOutboxEntity candidate : active) {
            if (!currentEventIds.contains(candidate.getEventId())) cancelSuperseded(candidate.getId());
        }
    }

    private void cancelSuperseded(Long outboxId) {
        MdmPlanResourceEventOutboxEntity outbox = outboxMapper.lockById(outboxId);
        if (outbox == null || "CANCELLED".equals(outbox.getSourceStatus())) return;
        String before = outbox.getStatus();
        outbox.setSourceStatus("CANCELLED").setSourceClosedTime(LocalDateTime.now());
        if (List.of("PENDING", "RETRY_WAIT", "DEAD_LETTER").contains(before)) {
            outbox.setStatus("CANCELLED").setNextAttemptTime(null).setLastErrorCode(null)
                    .setLastErrorMessage("MDM不可用事实已撤销或恢复，未投递事件同步取消");
        }
        require(outboxMapper.updateById(outbox) == 1, "MDM资源事实撤销状态保存失败");
        operation(outbox, "SOURCE-CANCEL:" + outbox.getEventId(), "SOURCE_CANCELLED", before,
                outbox.getStatus(), Objects.requireNonNullElse(outbox.getAttemptCount(), 0),
                "MDM不可用事实已被权威档案撤销或恢复；已投递事实保留审计，正式发布前重新核验实时状态");
    }

    @Override
    public Page<MdmPlanResourceEventOutboxEntity> page(SearchPage page, MdmPlanResourceEventDTO.Query query) {
        MdmPlanResourceEventDTO.Query q = query == null ? new MdmPlanResourceEventDTO.Query() : query;
        return outboxMapper.selectPage(page.toMpPage(), new LambdaQueryWrapper<MdmPlanResourceEventOutboxEntity>()
                .like(StrUtil.isNotBlank(q.getEventId()), MdmPlanResourceEventOutboxEntity::getEventId, q.getEventId())
                .eq(StrUtil.isNotBlank(q.getSourceType()), MdmPlanResourceEventOutboxEntity::getSourceType, q.getSourceType())
                .like(StrUtil.isNotBlank(q.getSourceNo()), MdmPlanResourceEventOutboxEntity::getSourceNo, q.getSourceNo())
                .eq(StrUtil.isNotBlank(q.getResourceType()), MdmPlanResourceEventOutboxEntity::getResourceType, q.getResourceType())
                .like(StrUtil.isNotBlank(q.getResourceCode()), MdmPlanResourceEventOutboxEntity::getResourceCode, q.getResourceCode())
                .eq(StrUtil.isNotBlank(q.getStatus()), MdmPlanResourceEventOutboxEntity::getStatus, q.getStatus())
                .orderByAsc(MdmPlanResourceEventOutboxEntity::getStatus)
                .orderByDesc(MdmPlanResourceEventOutboxEntity::getOccurredTime));
    }

    @Override
    public MdmPlanResourceEventDTO.Detail detail(Long outboxId) {
        MdmPlanResourceEventOutboxEntity outbox = requireOutbox(outboxId, false);
        MdmPlanResourceEventDTO.Detail detail = new MdmPlanResourceEventDTO.Detail();
        BeanUtils.copyProperties(outbox, detail);
        detail.setOperations(operationMapper.selectList(
                new LambdaQueryWrapper<MdmPlanResourceEventOperationEntity>()
                        .eq(MdmPlanResourceEventOperationEntity::getOutboxId, outboxId)
                        .orderByAsc(MdmPlanResourceEventOperationEntity::getOperationTime)
                        .orderByAsc(MdmPlanResourceEventOperationEntity::getId)));
        return detail;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean retry(MdmPlanResourceEventDTO.ManualActionCommand command) {
        require(command != null && command.getOutboxId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()), "人工重试参数不完整");
        String requestId = command.getRequestId().trim();
        String reason = command.getReason().trim();
        require(requestId.length() <= 128 && reason.length() <= 500, "请求号最多128位、重试原因最多500位");
        MdmPlanResourceEventOutboxEntity outbox = requireOutbox(command.getOutboxId(), true);
        if (manualReplay(outbox, requestId, reason)) return true;
        require(List.of("RETRY_WAIT", "DEAD_LETTER").contains(outbox.getStatus()), "仅退避或死信事件允许人工重试");
        require("ACTIVE".equals(outbox.getSourceStatus()) && (DEVICE_RECOVERY.equals(outbox.getSourceType())
                        || Boolean.TRUE.equals(outbox.getOpenEnded())
                        || outbox.getExpectedRecoveryTime() != null
                        && outbox.getExpectedRecoveryTime().isAfter(LocalDateTime.now())),
                "MDM来源已撤销或不可用事实已经结束");
        String before = outbox.getStatus();
        MomUser user = currentUser();
        outbox.setStatus("PENDING").setAttemptCount(0).setNextAttemptTime(LocalDateTime.now())
                .setLastErrorCode(null).setLastErrorMessage(null).setManualRequestId(requestId).setManualReason(reason)
                .setManualOperatorCode(userCode(user)).setManualOperatorName(userName(user))
                .setManualOperationTime(LocalDateTime.now());
        require(outboxMapper.updateById(outbox) == 1, "MDM资源事件人工重试状态保存失败");
        operation(outbox, requestId, "MANUAL_RETRY", before, "PENDING", 0, reason);
        return true;
    }

    @Override
    public int dispatchDue(int limit) {
        int batchSize = Math.max(1, Math.min(limit, 200));
        List<MdmPlanResourceEventOutboxEntity> expired = outboxMapper.selectPage(new Page<>(1, batchSize),
                new LambdaQueryWrapper<MdmPlanResourceEventOutboxEntity>()
                        .eq(MdmPlanResourceEventOutboxEntity::getSourceStatus, "ACTIVE")
                        .ne(MdmPlanResourceEventOutboxEntity::getSourceType, DEVICE_RECOVERY)
                        .eq(MdmPlanResourceEventOutboxEntity::getOpenEnded, false)
                        .le(MdmPlanResourceEventOutboxEntity::getExpectedRecoveryTime, LocalDateTime.now())
                        .orderByAsc(MdmPlanResourceEventOutboxEntity::getExpectedRecoveryTime)
                        .orderByAsc(MdmPlanResourceEventOutboxEntity::getId)).getRecords();
        for (MdmPlanResourceEventOutboxEntity item : expired) {
            try {
                if (item.getTenantId() == null) failureService.closeExpired(item.getId());
                else TenantBroker.applyAs(item.getTenantId(), ignored -> failureService.closeExpired(item.getId()));
            } catch (RuntimeException exception) {
                log.error("MDM资源事件到期关闭失败，eventId={}，tenantId={}", item.getEventId(), item.getTenantId(), exception);
            }
        }
        List<MdmPlanResourceEventOutboxEntity> due = outboxMapper.selectPage(new Page<>(1, batchSize),
                new LambdaQueryWrapper<MdmPlanResourceEventOutboxEntity>()
                        .in(MdmPlanResourceEventOutboxEntity::getStatus, "PENDING", "RETRY_WAIT")
                        .eq(MdmPlanResourceEventOutboxEntity::getSourceStatus, "ACTIVE")
                        .le(MdmPlanResourceEventOutboxEntity::getNextAttemptTime, LocalDateTime.now())
                        .orderByAsc(MdmPlanResourceEventOutboxEntity::getNextAttemptTime)
                        .orderByAsc(MdmPlanResourceEventOutboxEntity::getId)).getRecords();
        int delivered = 0;
        for (MdmPlanResourceEventOutboxEntity candidate : due) {
            try {
                boolean done = candidate.getTenantId() == null ? dispatchOne(candidate.getId())
                        : TenantBroker.applyAs(candidate.getTenantId(), ignored -> dispatchOne(candidate.getId()));
                if (done) delivered++;
            } catch (RuntimeException exception) {
                log.error("MDM资源事件调度异常，eventId={}，tenantId={}", candidate.getEventId(),
                        candidate.getTenantId(), exception);
            }
        }
        return delivered;
    }

    private boolean dispatchOne(Long outboxId) {
        try {
            return Boolean.TRUE.equals(worker.deliverOne(outboxId));
        } catch (RuntimeException exception) {
            boolean sourceClosed = exception instanceof MdmPlanResourceEventOutboxWorker.SourceClosedException;
            boolean retryable = !(exception instanceof MdmPlanResourceEventOutboxWorker.NonRetryableDeliveryException);
            failureService.markFailure(outboxId, exception, retryable, sourceClosed);
            return false;
        }
    }

    private ResourceFact toolingFact(MdmToolingEntity tooling, MdmToolingUnavailableWindowEntity window) {
        require(window.getStartTime() != null && window.getEndTime() != null
                && window.getEndTime().isAfter(window.getStartTime()), "工装不可用窗口起止时间不合法");
        if (!window.getEndTime().isAfter(LocalDateTime.now())) return null;
        String type = window.getWindowType().toUpperCase(Locale.ROOT);
        String reason = type + "：" + StrUtil.blankToDefault(window.getReason(), "工装计划不可用");
        return fact(TOOLING_WINDOW, window.getId(), "TW-" + window.getId(), "TOOLING",
                tooling.getToolingCode(), tooling.getToolingName(), type, window.getStartTime(), window.getEndTime(),
                false, reason, tooling.getFactoryCode(), Map.of("windowType", type));
    }

    private ResourceFact employeeFact(MdmEmployeeEntity employee, MdmEmployeeCalendarExceptionEntity exception) {
        String type = exception.getExceptionType().toUpperCase(Locale.ROOT);
        LocalDateTime start = exception.getStartTime() == null ? exception.getCalendarDate().atStartOfDay()
                : exception.getCalendarDate().atTime(exception.getStartTime());
        LocalDateTime end = exception.getEndTime() == null ? exception.getCalendarDate().plusDays(1).atStartOfDay()
                : exception.getCalendarDate().atTime(exception.getEndTime());
        if (!end.isAfter(start)) end = end.plusDays(1);
        if (!end.isAfter(LocalDateTime.now())) return null;
        String reason = type + "：" + StrUtil.blankToDefault(exception.getReason(), "人员计划不可用");
        return fact(EMPLOYEE_CALENDAR, exception.getId(), "EC-" + exception.getId(), "EMPLOYEE",
                employee.getEmployeeCode(), employee.getName(), type, start, end, reason, employee.getFactoryCode());
    }

    private ResourceFact toolingMasterFact(MdmToolingEntity tooling, String state) {
        LocalDateTime start = Optional.ofNullable(tooling.getUnavailableSince())
                .orElseGet(() -> LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS));
        String factType = Boolean.TRUE.equals(tooling.getStatus()) ? "TOOLING_STATE_" + state : "TOOLING_DISABLED";
        String reason = Boolean.TRUE.equals(tooling.getStatus())
                ? "工装主状态为" + state + "，当前不可排产" : "工装主档已停用，当前不可排产";
        return fact(TOOLING_MASTER, tooling.getId(), "TM-" + tooling.getId(), "TOOLING",
                tooling.getToolingCode(), tooling.getToolingName(), factType, start, null, true, reason,
                tooling.getFactoryCode(), Map.of("toolingState", state, "masterEnabled",
                        Boolean.TRUE.equals(tooling.getStatus())));
    }

    private ResourceFact qualificationFact(MdmEmployeeEntity employee, MdmEmployeeSkillEntity qualification) {
        boolean disabled = !Boolean.TRUE.equals(qualification.getStatus());
        if (!disabled && qualification.getValidTo() == null) return null;
        LocalDateTime start = disabled
                ? Optional.ofNullable(qualification.getInvalidTime())
                .orElseGet(() -> LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS))
                : qualification.getValidTo().plusDays(1).atStartOfDay();
        String factType = disabled ? "QUALIFICATION_DISABLED" : "QUALIFICATION_EXPIRED";
        String reason = "人员技能[" + qualification.getSkillCode() + "/"
                + StrUtil.blankToDefault(qualification.getSkillName(), qualification.getSkillCode()) + "]"
                + (disabled ? "已停用或从档案移除" : "将于" + qualification.getValidTo() + "到期")
                + "，自" + start + "起不再满足排产资质";
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("skillCode", qualification.getSkillCode());
        detail.put("skillName", qualification.getSkillName());
        detail.put("proficiencyLevel", qualification.getProficiencyLevel());
        detail.put("certificateNo", qualification.getCertificateNo());
        detail.put("validFrom", qualification.getValidFrom());
        detail.put("validTo", qualification.getValidTo());
        detail.put("qualificationEnabled", Boolean.TRUE.equals(qualification.getStatus()));
        return fact(EMPLOYEE_QUALIFICATION, qualification.getId(), "ES-" + qualification.getId(), "EMPLOYEE",
                employee.getEmployeeCode(), employee.getName(), factType, start, null, true, reason,
                employee.getFactoryCode(), detail);
    }

    private ResourceFact fact(String sourceType, Long sourceId, String sourceNo, String resourceType,
                              String resourceCode, String resourceName, String factType, LocalDateTime start,
                              LocalDateTime end, String reason, String factoryCode) {
        return fact(sourceType, sourceId, sourceNo, resourceType, resourceCode, resourceName, factType,
                start, end, false, reason, factoryCode, Map.of());
    }

    private ResourceFact fact(String sourceType, Long sourceId, String sourceNo, String resourceType,
                              String resourceCode, String resourceName, String factType, LocalDateTime start,
                              LocalDateTime end, boolean openEnded, String reason, String factoryCode,
                              Map<String, Object> details) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("sourceType", sourceType);
        source.put("sourceId", sourceId);
        source.put("sourceNo", sourceNo);
        source.put("resourceType", resourceType);
        source.put("resourceCode", resourceCode);
        source.put("resourceName", resourceName);
        source.put("factType", factType);
        source.put("eventStartTime", start);
        source.put("expectedRecoveryTime", end);
        source.put("openEnded", openEnded);
        source.putAll(details);
        source.put("reason", compact(reason, 1000));
        source.put("factoryCode", factoryCode);
        String sourceJson = json(source);
        String fingerprint = hash(sourceJson);
        String prefix = switch (sourceType) {
            case TOOLING_WINDOW -> "MDM-TOOLING-WINDOW:";
            case TOOLING_MASTER -> "MDM-TOOLING-MASTER:";
            case EMPLOYEE_CALENDAR -> "MDM-EMPLOYEE-CALENDAR:";
            case EMPLOYEE_QUALIFICATION -> "MDM-EMPLOYEE-QUALIFICATION:";
            default -> "MDM-RESOURCE:";
        };
        String eventId = prefix + sourceId + ":UNAVAILABLE"
                + (openEnded ? ':' + fingerprint.substring(0, 16) : "");
        return new ResourceFact(sourceId, sourceNo, eventId, factType, start, end, openEnded,
                compact(reason, 1000), sourceJson, fingerprint, details);
    }

    private String payload(String sourceType, String resourceType, String resourceCode, String resourceName,
                           ResourceFact fact, String factoryCode) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("resourceType", resourceType);
        value.put("resourceCode", resourceCode);
        value.put("resourceName", resourceName);
        value.put("eventStartTime", fact.start());
        value.put("expectedRecoveryTime", fact.end());
        value.put("openEnded", fact.openEnded());
        value.put("factType", fact.factType());
        value.putAll(fact.details());
        value.put("reason", fact.reason());
        value.put("sourceType", sourceType);
        value.put("sourceNo", fact.sourceNo());
        value.put("sourceFingerprint", fact.sourceFingerprint());
        value.put("factoryCode", factoryCode);
        value.put("maxAffectedIssues", 100);
        return json(value);
    }

    private MdmPlanResourceEventOutboxEntity requireOutbox(Long id, boolean lock) {
        require(id != null, "Outbox ID不能为空");
        MdmPlanResourceEventOutboxEntity outbox = lock ? outboxMapper.lockById(id) : outboxMapper.selectById(id);
        require(outbox != null, "MDM资源事件不存在");
        String currentFactory = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(currentFactory) || Objects.equals(currentFactory, outbox.getFactoryCode()),
                "请求工厂超出当前数据范围");
        return outbox;
    }

    private boolean manualReplay(MdmPlanResourceEventOutboxEntity outbox, String requestId, String reason) {
        MdmPlanResourceEventOperationEntity duplicate = operationMapper.selectOne(
                new LambdaQueryWrapper<MdmPlanResourceEventOperationEntity>()
                        .eq(MdmPlanResourceEventOperationEntity::getRequestId, requestId));
        if (duplicate == null) return false;
        require(Objects.equals(duplicate.getOutboxId(), outbox.getId())
                        && "MANUAL_RETRY".equals(duplicate.getOperationType())
                        && duplicate.getContent() != null && duplicate.getContent().startsWith(reason),
                "人工操作幂等号已被不同事件、动作或内容使用");
        return true;
    }

    private void operation(MdmPlanResourceEventOutboxEntity outbox, String requestId, String type,
                           String before, String after, int attempt, String content) {
        MomUser user = currentUser();
        require(operationMapper.insert(new MdmPlanResourceEventOperationEntity().setOutboxId(outbox.getId())
                .setRequestId(requestId).setOperationType(type).setBeforeStatus(before).setAfterStatus(after)
                .setAttemptNo(attempt).setContent(compact(content, 1000)).setOperatorCode(userCode(user))
                .setOperatorName(userName(user)).setOperationTime(LocalDateTime.now())
                .setFactoryCode(outbox.getFactoryCode())) == 1, "MDM资源事件操作履历保存失败");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new CheckedException("MDM资源事实序列化失败");
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("MDM资源事实摘要生成失败");
        }
    }

    private MomUser currentUser() {
        try {
            return SecurityUtils.getUser();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String userCode(MomUser user) {
        return user == null ? "SYSTEM" : user.getUsername();
    }

    private String userName(MomUser user) {
        return user == null ? "系统" : StrUtil.blankToDefault(user.getName(), user.getUsername());
    }

    private record ResourceFact(Long sourceId, String sourceNo, String eventId, String factType,
                                LocalDateTime start, LocalDateTime end, boolean openEnded, String reason,
                                String sourceJson, String sourceFingerprint, Map<String, Object> details) {
    }
}
