package com.kitex.mom.tpm.service.impl;

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
import com.kitex.mom.tpm.dto.TpmPlanEventDTO;
import com.kitex.mom.tpm.entity.*;
import com.kitex.mom.tpm.mapper.*;
import com.kitex.mom.tpm.service.TpmPlanEventOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class TpmPlanEventOutboxServiceImpl implements TpmPlanEventOutboxService {
    private static final String SOURCE_REPAIR = "TPM_REPAIR_TASK";
    private static final String SOURCE_MAINTENANCE = "TPM_MAINTENANCE_TASK";
    private static final String SOURCE_SCRAP = "TPM_SCRAP_APPLY";
    private static final String SOURCE_CHECK = "TPM_CHECK_TASK";

    private final TpmPlanEventOutboxMapper outboxMapper;
    private final TpmPlanEventDeliveryOperationMapper operationMapper;
    private final TpmRepairTaskMapper repairTaskMapper;
    private final TpmMaintenanceTaskMapper maintenanceTaskMapper;
    private final TpmCheckTaskMapper checkTaskMapper;
    private final TpmPlanEventOutboxWorker worker;
    private final TpmPlanEventOutboxFailureService failureService;
    private final ObjectMapper objectMapper;

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TpmPlanEventOutboxEntity enqueueRepairUnavailable(TpmRepairTaskEntity task) {
        require(task != null && task.getId() != null && StrUtil.isNotBlank(task.getDeviceCode())
                && task.getSubmitTime() != null && StrUtil.isNotBlank(task.getFactoryCode()), "维修任务设备事件参数不完整");
        String eventId = "TPM-REPAIR:" + task.getId() + ":UNAVAILABLE";
        outboxMapper.lockKey(eventId);
        TpmPlanEventOutboxEntity existing = outboxMapper.selectOne(new LambdaQueryWrapper<TpmPlanEventOutboxEntity>()
                .eq(TpmPlanEventOutboxEntity::getEventId, eventId));
        if (existing != null) return existing;
        LocalDateTime recovery = task.getExpectedRecoveryTime();
        if (recovery != null) require(recovery.isAfter(task.getSubmitTime()), "设备预计恢复时间必须晚于报修时间");
        if (recovery != null)
            require(StrUtil.isNotBlank(task.getRecoveryForecastReason()), "填写预计恢复时间时必须同时提供预测依据");
        String reason = StrUtil.blankToDefault(task.getFaultDesc(),
                StrUtil.blankToDefault(task.getFaultReason(), "维修任务" + task.getTaskCode() + "导致设备不可用"));
        String payload = payload(task.getDeviceCode(), task.getDeviceName(), task.getSubmitTime(), recovery, reason,
                task.getRecoveryForecastReason(), task.getTaskCode());
        LocalDateTime now = LocalDateTime.now();
        TpmPlanEventOutboxEntity outbox = new TpmPlanEventOutboxEntity().setEventId(eventId)
                .setEventType("RESOURCE_UNAVAILABLE").setEventVersion(1).setSourceType(SOURCE_REPAIR)
                .setSourceId(task.getId()).setSourceNo(task.getTaskCode()).setDeviceCode(task.getDeviceCode())
                .setDeviceName(task.getDeviceName()).setOccurredTime(task.getSubmitTime()).setEventStartTime(task.getSubmitTime())
                .setExpectedRecoveryTime(recovery).setReason(reason).setRecoveryForecastReason(task.getRecoveryForecastReason())
                .setPayloadJson(payload).setPayloadFingerprint(hash(payload))
                .setStatus(recovery == null ? "WAITING_DATA" : "PENDING").setAttemptCount(0).setMaxAttempts(8)
                .setNextAttemptTime(recovery == null ? null : now).setSourceStatus("UNAVAILABLE").setFactoryCode(task.getFactoryCode());
        require(outboxMapper.insert(outbox) == 1, "维修设备事件Outbox保存失败");
        operation(outbox, "CREATE:" + eventId, "OUTBOX_CREATED", null, outbox.getStatus(), 0,
                recovery == null ? "维修事实已入箱，等待补充预计恢复时间" : "维修事实及恢复预测已入箱，等待投递PLAN");
        return outbox;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TpmPlanEventOutboxEntity enqueueMaintenanceUnavailable(TpmMaintenanceTaskEntity task,
                                                                  LocalDateTime eventStartTime,
                                                                  LocalDateTime expectedRecoveryTime) {
        require(task != null && task.getId() != null && StrUtil.isNotBlank(task.getDeviceCode())
                        && StrUtil.isNotBlank(task.getFactoryCode()) && eventStartTime != null && expectedRecoveryTime != null,
                "保养任务设备事件参数不完整");
        require(expectedRecoveryTime.isAfter(eventStartTime), "保养预计恢复时间必须晚于实际开始时间");
        String reason = "保养任务" + task.getTaskCode() + "已实际开始，设备进入保养不可用状态";
        String basis = "按保养任务标准处理时长" + task.getHandleTime() + task.getHandleTimeUnit() + "自实际开始时间推算";
        return enqueue(SOURCE_MAINTENANCE, task.getId(), task.getTaskCode(), task.getDeviceCode(), task.getDeviceName(),
                eventStartTime, expectedRecoveryTime, reason, basis, task.getFactoryCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markMaintenanceRecovered(TpmMaintenanceTaskEntity task) {
        if (task == null || task.getId() == null) return;
        markSourceRecovered(SOURCE_MAINTENANCE, task.getId(),
                Objects.requireNonNullElse(task.getMaintenanceTime(), LocalDateTime.now()), "保养任务已完成", false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TpmPlanEventOutboxEntity enqueueScrapUnavailable(TpmScrapApplyEntity apply) {
        require(apply != null && apply.getId() != null && StrUtil.isNotBlank(apply.getDeviceCode())
                        && StrUtil.isNotBlank(apply.getFactoryCode()) && apply.getReviewerTime() != null,
                "报废审批设备事件参数不完整");
        String reason = StrUtil.blankToDefault(apply.getApplyReason(), "设备报废审批" + apply.getApplyCode() + "已通过");
        // 报废是永久不可用事实，不伪造恢复时间；计划员需补充替代设备到位/产能恢复窗口后才触发候选。
        TpmPlanEventOutboxEntity outbox = enqueue(SOURCE_SCRAP, apply.getId(), apply.getApplyCode(), apply.getDeviceCode(), apply.getDeviceName(),
                apply.getReviewerTime(), null, reason, null, apply.getFactoryCode());
        cancelSupersededDeviceEvents(apply, outbox);
        return outbox;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TpmPlanEventOutboxEntity enqueueCheckUnavailable(TpmCheckTaskEntity task) {
        require(task != null && task.getId() != null && StrUtil.isNotBlank(task.getTaskCode())
                        && StrUtil.isNotBlank(task.getDeviceCode()) && StrUtil.isNotBlank(task.getFactoryCode())
                        && task.getBlockTime() != null && "BLOCKED".equals(task.getBlockStatus()),
                "点检封锁设备事件参数不完整");
        LocalDateTime recovery = task.getExpectedRecoveryTime();
        if (recovery != null) require(recovery.isAfter(task.getBlockTime()), "点检封锁预计恢复时间必须晚于封锁时间");
        if (recovery != null)
            require(StrUtil.isNotBlank(task.getRecoveryForecastReason()), "点检封锁填写预计恢复时间时必须提供预测依据");
        return enqueue(SOURCE_CHECK, task.getId(), task.getTaskCode(), task.getDeviceCode(), task.getDeviceName(),
                task.getBlockTime(), recovery, task.getBlockReason(), task.getRecoveryForecastReason(), task.getFactoryCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markCheckRecovered(TpmCheckTaskEntity task, String releaseEvidence) {
        if (task == null || task.getId() == null) return;
        markSourceRecovered(SOURCE_CHECK, task.getId(), Objects.requireNonNullElse(task.getReleasedTime(), LocalDateTime.now()),
                "点检生产封锁已受控解除；证据=" + StrUtil.blankToDefault(releaseEvidence, "未提供"), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRepairRecovered(TpmRepairTaskEntity task) {
        if (task == null || task.getId() == null) return;
        markSourceRecovered(SOURCE_REPAIR, task.getId(),
                Objects.requireNonNullElse(task.getRepairTime(), LocalDateTime.now()), "维修任务已完成", false);
    }

    private void markSourceRecovered(String sourceType, Long sourceId, LocalDateTime recoveredTime, String explanation,
                                     boolean requireExisting) {
        String eventId = eventId(sourceType, sourceId);
        outboxMapper.lockKey(eventId);
        TpmPlanEventOutboxEntity found = outboxMapper.selectOne(new LambdaQueryWrapper<TpmPlanEventOutboxEntity>()
                .eq(TpmPlanEventOutboxEntity::getEventId, eventId));
        if (found == null) {
            require(!requireExisting, "点检封锁对应的设备事件Outbox不存在，禁止形成无法对账的解除结果");
            return;
        }
        TpmPlanEventOutboxEntity outbox = outboxMapper.lockById(found.getId());
        if ("RECOVERED".equals(outbox.getSourceStatus())) return;
        String before = outbox.getStatus();
        outbox.setSourceStatus("RECOVERED").setSourceRecoveredTime(recoveredTime);
        if (List.of("WAITING_DATA", "PENDING", "RETRY_WAIT", "DEAD_LETTER").contains(before)) {
            outbox.setStatus("CANCELLED").setNextAttemptTime(null).setLastErrorCode(null)
                    .setLastErrorMessage("设备在事件投递前已恢复，已取消过期异常事实");
        }
        require(outboxMapper.updateById(outbox) == 1, "设备恢复状态同步Outbox失败");
        operation(outbox, "RECOVERED:" + eventId, "SOURCE_RECOVERED", before, outbox.getStatus(),
                Objects.requireNonNullElse(outbox.getAttemptCount(), 0),
                explanation + "，恢复时间=" + recoveredTime + ("CANCELLED".equals(outbox.getStatus())
                        ? "，未投递事件已取消" : "，已投递事件保留审计并由PLAN发布前复核"));
    }

    @Override
    public Page<TpmPlanEventOutboxEntity> page(SearchPage page, TpmPlanEventDTO.Query query) {
        TpmPlanEventDTO.Query q = query == null ? new TpmPlanEventDTO.Query() : query;
        return outboxMapper.selectPage(page.toMpPage(), new LambdaQueryWrapper<TpmPlanEventOutboxEntity>()
                .like(StrUtil.isNotBlank(q.getEventId()), TpmPlanEventOutboxEntity::getEventId, q.getEventId())
                .eq(StrUtil.isNotBlank(q.getSourceType()), TpmPlanEventOutboxEntity::getSourceType, q.getSourceType())
                .like(StrUtil.isNotBlank(q.getSourceNo()), TpmPlanEventOutboxEntity::getSourceNo, q.getSourceNo())
                .eq(StrUtil.isNotBlank(q.getDeviceCode()), TpmPlanEventOutboxEntity::getDeviceCode, q.getDeviceCode())
                .eq(StrUtil.isNotBlank(q.getStatus()), TpmPlanEventOutboxEntity::getStatus, q.getStatus())
                .orderByAsc(TpmPlanEventOutboxEntity::getStatus).orderByDesc(TpmPlanEventOutboxEntity::getOccurredTime));
    }

    @Override
    public TpmPlanEventDTO.Detail detail(Long outboxId) {
        TpmPlanEventOutboxEntity outbox = requireOutbox(outboxId, false);
        TpmPlanEventDTO.Detail detail = new TpmPlanEventDTO.Detail();
        BeanUtils.copyProperties(outbox, detail);
        detail.setOperations(operationMapper.selectList(new LambdaQueryWrapper<TpmPlanEventDeliveryOperationEntity>()
                .eq(TpmPlanEventDeliveryOperationEntity::getOutboxId, outboxId)
                .orderByAsc(TpmPlanEventDeliveryOperationEntity::getOperationTime)
                .orderByAsc(TpmPlanEventDeliveryOperationEntity::getId)));
        return detail;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TpmPlanEventOutboxEntity supplyForecast(TpmPlanEventDTO.ForecastCommand command) {
        require(command != null && command.getOutboxId() != null && command.getExpectedRecoveryTime() != null
                && StrUtil.isNotBlank(command.getRequestId()) && StrUtil.isNotBlank(command.getReason()), "恢复预测补充参数不完整");
        TpmPlanEventOutboxEntity outbox = requireOutbox(command.getOutboxId(), true);
        if (manualReplay(outbox, command.getRequestId(), "FORECAST_SUPPLIED", command.getReason())) return outbox;
        require(List.of("WAITING_DATA", "PENDING").contains(outbox.getStatus())
                        && Objects.requireNonNullElse(outbox.getAttemptCount(), 0) == 0,
                "事件已开始投递、退避、死信、成功或取消，禁止修改同一事件ID的原始预测证据");
        require("UNAVAILABLE".equals(outbox.getSourceStatus()), "设备已恢复，无需补充恢复预测");
        require(command.getExpectedRecoveryTime().isAfter(outbox.getEventStartTime()), "预计恢复时间必须晚于异常开始时间");
        String before = outbox.getStatus();
        String payload = payload(outbox.getDeviceCode(), outbox.getDeviceName(), outbox.getEventStartTime(),
                command.getExpectedRecoveryTime(), outbox.getReason(), command.getReason().trim(), outbox.getSourceNo());
        MomUser user = currentUser();
        outbox.setExpectedRecoveryTime(command.getExpectedRecoveryTime()).setRecoveryForecastReason(command.getReason().trim())
                .setPayloadJson(payload).setPayloadFingerprint(hash(payload)).setStatus("PENDING").setAttemptCount(0)
                .setNextAttemptTime(LocalDateTime.now()).setLastErrorCode(null).setLastErrorMessage(null)
                .setManualRequestId(command.getRequestId().trim()).setManualReason(command.getReason().trim())
                .setManualOperatorCode(userCode(user)).setManualOperatorName(userName(user)).setManualOperationTime(LocalDateTime.now());
        require(outboxMapper.updateById(outbox) == 1, "恢复预测与待投递状态保存失败");
        if (SOURCE_REPAIR.equals(outbox.getSourceType())) {
            TpmRepairTaskEntity task = repairTaskMapper.selectById(outbox.getSourceId());
            require(task != null, "关联维修任务不存在");
            task.setExpectedRecoveryTime(command.getExpectedRecoveryTime());
            task.setRecoveryForecastReason(command.getReason().trim());
            task.setForecastUpdatedTime(LocalDateTime.now());
            task.setForecastUpdatedBy(userCode(user));
            require(repairTaskMapper.updateById(task) == 1, "维修任务恢复预测保存失败");
        } else if (SOURCE_MAINTENANCE.equals(outbox.getSourceType())) {
            TpmMaintenanceTaskEntity task = maintenanceTaskMapper.selectById(outbox.getSourceId());
            require(task != null, "关联保养任务不存在");
            task.setExpectedRecoveryTime(command.getExpectedRecoveryTime());
            task.setRecoveryForecastReason(command.getReason().trim());
            require(maintenanceTaskMapper.updateById(task) == 1, "保养任务恢复预测保存失败");
        } else if (SOURCE_CHECK.equals(outbox.getSourceType())) {
            TpmCheckTaskEntity task = checkTaskMapper.selectById(outbox.getSourceId());
            require(task != null && "BLOCKED".equals(task.getBlockStatus())
                    && Objects.equals(task.getFactoryCode(), outbox.getFactoryCode()), "关联点检封锁不存在、已解除或不属于当前工厂");
            task.setExpectedRecoveryTime(command.getExpectedRecoveryTime());
            task.setRecoveryForecastReason(command.getReason().trim());
            require(checkTaskMapper.updateById(task) == 1, "点检封锁恢复预测保存失败");
        }
        operation(outbox, command.getRequestId().trim(), "FORECAST_SUPPLIED", before, "PENDING", 0,
                command.getReason().trim() + "；预计恢复=" + command.getExpectedRecoveryTime());
        return outbox;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean retry(TpmPlanEventDTO.ManualActionCommand command) {
        validateManual(command);
        TpmPlanEventOutboxEntity outbox = requireOutbox(command.getOutboxId(), true);
        if (manualReplay(outbox, command.getRequestId(), "MANUAL_RETRY", command.getReason())) return true;
        require(List.of("RETRY_WAIT", "DEAD_LETTER").contains(outbox.getStatus()), "仅退避或死信事件允许人工重试");
        require(outbox.getExpectedRecoveryTime() != null, "预计恢复时间未补齐，请先补充预测");
        require("UNAVAILABLE".equals(outbox.getSourceStatus()), "设备已恢复，禁止重放过期异常事件");
        String before = outbox.getStatus();
        applyManual(outbox, command);
        outbox.setStatus("PENDING").setAttemptCount(0).setNextAttemptTime(LocalDateTime.now())
                .setLastErrorCode(null).setLastErrorMessage(null);
        require(outboxMapper.updateById(outbox) == 1, "人工重试状态保存失败");
        operation(outbox, command.getRequestId().trim(), "MANUAL_RETRY", before, "PENDING", 0, command.getReason().trim());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancel(TpmPlanEventDTO.ManualActionCommand command) {
        validateManual(command);
        TpmPlanEventOutboxEntity outbox = requireOutbox(command.getOutboxId(), true);
        if (manualReplay(outbox, command.getRequestId(), "MANUAL_CANCEL", command.getReason())) return true;
        require(!List.of("DELIVERED", "CANCELLED", "PROCESSING").contains(outbox.getStatus()), "已投递、已取消或处理中事件不允许取消");
        String before = outbox.getStatus();
        applyManual(outbox, command);
        outbox.setStatus("CANCELLED").setNextAttemptTime(null);
        require(outboxMapper.updateById(outbox) == 1, "人工取消状态保存失败");
        operation(outbox, command.getRequestId().trim(), "MANUAL_CANCEL", before, "CANCELLED",
                Objects.requireNonNullElse(outbox.getAttemptCount(), 0), command.getReason().trim());
        return true;
    }

    @Override
    public int dispatchDue(int limit) {
        int batchSize = Math.max(1, Math.min(limit, 200));
        List<TpmPlanEventOutboxEntity> due = outboxMapper.selectPage(new Page<>(1, batchSize),
                new LambdaQueryWrapper<TpmPlanEventOutboxEntity>()
                        .in(TpmPlanEventOutboxEntity::getStatus, "PENDING", "RETRY_WAIT")
                        .le(TpmPlanEventOutboxEntity::getNextAttemptTime, LocalDateTime.now())
                        .orderByAsc(TpmPlanEventOutboxEntity::getNextAttemptTime)
                        .orderByAsc(TpmPlanEventOutboxEntity::getId)).getRecords();
        int delivered = 0;
        for (TpmPlanEventOutboxEntity candidate : due) {
            try {
                Boolean result = candidate.getTenantId() == null ? worker.deliverOne(candidate.getId())
                        : TenantBroker.applyAs(candidate.getTenantId(), ignored -> worker.deliverOne(candidate.getId()));
                if (Boolean.TRUE.equals(result)) delivered++;
            } catch (RuntimeException ex) {
                boolean retryable = !(ex instanceof TpmPlanEventOutboxWorker.NonRetryableDeliveryException);
                try {
                    if (candidate.getTenantId() == null) failureService.markFailure(candidate.getId(), ex, retryable);
                    else
                        TenantBroker.applyAs(candidate.getTenantId(), ignored -> failureService.markFailure(candidate.getId(), ex, retryable));
                } catch (RuntimeException failureEx) {
                    log.error("TPM设备事件投递失败状态保存异常，eventId={}，tenantId={}", candidate.getEventId(), candidate.getTenantId(), failureEx);
                }
            }
        }
        return delivered;
    }

    private String payload(String deviceCode, String deviceName, LocalDateTime start, LocalDateTime recovery,
                           String reason, String forecastReason, String sourceNo) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resourceType", "DEVICE");
        payload.put("resourceCode", deviceCode);
        payload.put("resourceName", deviceName);
        payload.put("eventStartTime", start);
        payload.put("expectedRecoveryTime", recovery);
        payload.put("reason", reason);
        payload.put("recoveryForecastBasis", forecastReason);
        payload.put("sourceTaskNo", sourceNo);
        payload.put("maxAffectedIssues", 50);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new CheckedException("TPM设备事件事实序列化失败");
        }
    }

    private TpmPlanEventOutboxEntity enqueue(String sourceType, Long sourceId, String sourceNo,
                                             String deviceCode, String deviceName, LocalDateTime eventStart,
                                             LocalDateTime recovery, String reason, String forecastReason,
                                             String factoryCode) {
        String id = eventId(sourceType, sourceId);
        outboxMapper.lockKey(id);
        TpmPlanEventOutboxEntity existing = outboxMapper.selectOne(new LambdaQueryWrapper<TpmPlanEventOutboxEntity>()
                .eq(TpmPlanEventOutboxEntity::getEventId, id));
        if (existing != null) return existing;
        String serialized = payload(deviceCode, deviceName, eventStart, recovery, reason, forecastReason, sourceNo);
        LocalDateTime now = LocalDateTime.now();
        TpmPlanEventOutboxEntity outbox = new TpmPlanEventOutboxEntity().setEventId(id)
                .setEventType("RESOURCE_UNAVAILABLE").setEventVersion(1).setSourceType(sourceType).setSourceId(sourceId)
                .setSourceNo(sourceNo).setDeviceCode(deviceCode).setDeviceName(deviceName).setOccurredTime(eventStart)
                .setEventStartTime(eventStart).setExpectedRecoveryTime(recovery).setReason(reason)
                .setRecoveryForecastReason(forecastReason).setPayloadJson(serialized).setPayloadFingerprint(hash(serialized))
                .setStatus(recovery == null ? "WAITING_DATA" : "PENDING").setAttemptCount(0).setMaxAttempts(8)
                .setNextAttemptTime(recovery == null ? null : now).setSourceStatus("UNAVAILABLE").setFactoryCode(factoryCode);
        require(outboxMapper.insert(outbox) == 1, "设备资源异常Outbox保存失败");
        operation(outbox, "CREATE:" + id, "OUTBOX_CREATED", null, outbox.getStatus(), 0,
                recovery == null ? "设备不可用事实已入箱，等待补充替代产能/恢复窗口" : "设备不可用事实已入箱，等待投递PLAN");
        return outbox;
    }

    private String eventId(String sourceType, Long sourceId) {
        String prefix = SOURCE_REPAIR.equals(sourceType) ? "REPAIR"
                : SOURCE_MAINTENANCE.equals(sourceType) ? "MAINTENANCE"
                : SOURCE_CHECK.equals(sourceType) ? "CHECK" : "SCRAP";
        return "TPM-" + prefix + ':' + sourceId + ":UNAVAILABLE";
    }

    /**
     * 报废事实优先于同设备尚未送达的维修/保养事实，避免PLAN收到互相冲突的旧不可用窗口。
     */
    private void cancelSupersededDeviceEvents(TpmScrapApplyEntity apply, TpmPlanEventOutboxEntity scrapOutbox) {
        List<TpmPlanEventOutboxEntity> candidates = outboxMapper.selectList(
                new LambdaQueryWrapper<TpmPlanEventOutboxEntity>()
                        .eq(TpmPlanEventOutboxEntity::getDeviceCode, apply.getDeviceCode())
                        .eq(TpmPlanEventOutboxEntity::getFactoryCode, apply.getFactoryCode())
                        .in(TpmPlanEventOutboxEntity::getSourceType, SOURCE_REPAIR, SOURCE_MAINTENANCE)
                        .in(TpmPlanEventOutboxEntity::getStatus,
                                "WAITING_DATA", "PENDING", "RETRY_WAIT", "DEAD_LETTER")
                        .orderByAsc(TpmPlanEventOutboxEntity::getId));
        for (TpmPlanEventOutboxEntity candidate : candidates) {
            TpmPlanEventOutboxEntity locked = outboxMapper.lockById(candidate.getId());
            if (locked == null || !List.of("WAITING_DATA", "PENDING", "RETRY_WAIT", "DEAD_LETTER")
                    .contains(locked.getStatus())) continue;
            String before = locked.getStatus();
            locked.setStatus("CANCELLED").setNextAttemptTime(null).setLastErrorCode("SOURCE_SUPERSEDED")
                    .setLastErrorMessage("设备报废审批已通过，旧维修/保养事件不再作为排产事实投递");
            require(outboxMapper.updateById(locked) == 1, "设备报废替代旧事件状态保存失败");
            operation(locked, "SUPERSEDED:SCRAP:" + apply.getId() + ':' + locked.getId(), "SOURCE_SUPERSEDED",
                    before, "CANCELLED", Objects.requireNonNullElse(locked.getAttemptCount(), 0),
                    "被报废事件" + scrapOutbox.getEventId() + "替代；已投递或处理中事件不覆盖，由PLAN发布前复核TPM实时事实");
        }
    }

    private TpmPlanEventOutboxEntity requireOutbox(Long id, boolean lock) {
        require(id != null, "Outbox ID不能为空");
        TpmPlanEventOutboxEntity outbox = lock ? outboxMapper.lockById(id) : outboxMapper.selectById(id);
        require(outbox != null, "TPM设备事件不存在");
        requireFactory(outbox.getFactoryCode());
        return outbox;
    }

    private boolean manualReplay(TpmPlanEventOutboxEntity outbox, String requestId, String type, String content) {
        TpmPlanEventDeliveryOperationEntity duplicate = operationMapper.selectOne(
                new LambdaQueryWrapper<TpmPlanEventDeliveryOperationEntity>()
                        .eq(TpmPlanEventDeliveryOperationEntity::getRequestId, requestId.trim()));
        if (duplicate == null) return false;
        require(Objects.equals(duplicate.getOutboxId(), outbox.getId()) && Objects.equals(duplicate.getOperationType(), type)
                && duplicate.getContent() != null && duplicate.getContent().startsWith(content.trim()), "人工操作幂等号已被不同事件、动作或内容使用");
        return true;
    }

    private void applyManual(TpmPlanEventOutboxEntity outbox, TpmPlanEventDTO.ManualActionCommand command) {
        MomUser user = currentUser();
        outbox.setManualRequestId(command.getRequestId().trim()).setManualReason(command.getReason().trim())
                .setManualOperatorCode(userCode(user)).setManualOperatorName(userName(user)).setManualOperationTime(LocalDateTime.now());
    }

    private void operation(TpmPlanEventOutboxEntity outbox, String requestId, String type, String before,
                           String after, int attempt, String content) {
        MomUser user = currentUser();
        require(operationMapper.insert(new TpmPlanEventDeliveryOperationEntity().setOutboxId(outbox.getId())
                .setRequestId(requestId).setOperationType(type).setBeforeStatus(before).setAfterStatus(after)
                .setAttemptNo(attempt).setContent(content).setOperatorCode(userCode(user)).setOperatorName(userName(user))
                .setOperationTime(LocalDateTime.now()).setFactoryCode(outbox.getFactoryCode())) == 1, "TPM设备事件操作履历保存失败");
    }

    private void validateManual(TpmPlanEventDTO.ManualActionCommand command) {
        require(command != null && command.getOutboxId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()), "人工处置参数不完整");
    }

    private String hash(String raw) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
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

    private String userCode(MomUser user) {
        return user == null ? "SYSTEM" : user.getUsername();
    }

    private String userName(MomUser user) {
        return user == null ? "系统" : StrUtil.blankToDefault(user.getName(), user.getUsername());
    }

    private void requireFactory(String factoryCode) {
        String current = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(current) || Objects.equals(current, factoryCode), "请求工厂超出当前数据范围");
    }
}
