package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mes.dto.MesRepairResumeEvidence;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 真正开工切段时记录MES→PLAN执行事实；批准本身绝不生成复工事件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MesRepairResumeEventService {
    private final MesRepairResumeOutboxMapper outboxes;
    private final MesRepairResumeDeliveryOperationMapper operations;
    private final MesRepairArrangeStopMapper stops;
    private final MesRepairProductionReleaseMapper releases;
    private final MesTaskArrangeMapper arranges;
    private final MesTaskExecutionSegmentMapper segments;
    private final MesTaskMapper tasks;
    private final RemoteMdmFactoryAccessService factories;
    private final MesRepairResumeEventWorker worker;
    private final MesRepairResumeEventFailureService failures;

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    static void record(MesRepairResumeDeliveryOperationMapper mapper, MesRepairResumeOutbox outbox,
                       String action, String before, String after, int attempt, String requestId, String detail) {
        MesRepairResumeDeliveryOperation operation = new MesRepairResumeDeliveryOperation();
        operation.setTenantId(outbox.getTenantId());
        operation.setFactoryCode(outbox.getFactoryCode());
        operation.setOutboxId(outbox.getId());
        operation.setAction(action);
        operation.setAttemptNo(attempt);
        operation.setBeforeStatus(before);
        operation.setAfterStatus(after);
        operation.setRequestId(requestId);
        operation.setDetail(StrUtil.sub(detail, 0, 500));
        operation.setOperationTime(LocalDateTime.now());
        require(mapper.insert(operation) == 1, "维修复工投递永久履历保存失败");
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordActualResume(MesTaskArrangeEntity arrange, MesTaskExecutionSegmentEntity segment) {
        require(arrange != null && segment != null && "PRODUCT".equals(arrange.getStatus())
                        && "OPEN".equals(segment.getStatus()) && Objects.equals(segment.getArrangeId(), arrange.getId()),
                "真实复工来源须为本派工新打开的加工时间段");
        MesRepairArrangeStop stop = stops.selectOne(new LambdaQueryWrapper<MesRepairArrangeStop>()
                .eq(MesRepairArrangeStop::getTenantId, arrange.getTenantId())
                .eq(MesRepairArrangeStop::getFactoryCode, arrange.getFactoryCode())
                .eq(MesRepairArrangeStop::getArrangeId, arrange.getId())
                .orderByDesc(MesRepairArrangeStop::getId).last("LIMIT 1"));
        if (stop == null) return;
        long alreadyRecorded = outboxes.selectCount(new LambdaQueryWrapper<MesRepairResumeOutbox>()
                .eq(MesRepairResumeOutbox::getTenantId, arrange.getTenantId())
                .eq(MesRepairResumeOutbox::getFactoryCode, arrange.getFactoryCode())
                .eq(MesRepairResumeOutbox::getStopId, stop.getId()));
        if (alreadyRecorded > 0) return;
        long earlierRun = segments.selectCount(new LambdaQueryWrapper<MesTaskExecutionSegmentEntity>()
                .eq(MesTaskExecutionSegmentEntity::getTenantId, arrange.getTenantId())
                .eq(MesTaskExecutionSegmentEntity::getFactoryCode, arrange.getFactoryCode())
                .eq(MesTaskExecutionSegmentEntity::getArrangeId, arrange.getId())
                .eq(MesTaskExecutionSegmentEntity::getSegmentType, "RUN")
                .gt(MesTaskExecutionSegmentEntity::getStartTime, stop.getStopTime())
                .lt(MesTaskExecutionSegmentEntity::getStartTime, segment.getStartTime()));
        if (earlierRun > 0) return; // 历史已复工不能拿后续普通复工倒填“首次维修后复工”。
        MesRepairProductionRelease release = releases.selectOne(new LambdaQueryWrapper<MesRepairProductionRelease>()
                .eq(MesRepairProductionRelease::getTenantId, arrange.getTenantId())
                .eq(MesRepairProductionRelease::getFactoryCode, arrange.getFactoryCode())
                .eq(MesRepairProductionRelease::getStopId, stop.getId())
                .orderByDesc(MesRepairProductionRelease::getId).last("LIMIT 1"));
        require(release != null && "APPROVE".equals(release.getAction())
                && Objects.equals(release.getArrangeId(), arrange.getId()), "维修复工事件缺少当前MES独立批准");
        MesTaskEntity task = tasks.selectOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, arrange.getTenantId())
                .eq(MesTaskEntity::getFactoryCode, arrange.getFactoryCode())
                .eq(MesTaskEntity::getCode, arrange.getTaskCode()).last("LIMIT 1"));
        require(task != null && StrUtil.isNotBlank(arrange.getRouteCode())
                        && Objects.equals(segment.getProcessCode(), arrange.getRouteCode())
                        && Objects.equals(segment.getDeviceCode(), stop.getDeviceCode()),
                "维修复工事件缺少准确PLAN计划、工序或设备来源");
        if (StrUtil.isBlank(task.getPlanNo())) return; // 非PLAN来源派工不伪造计划号，也不阻断真实MES复工。
        Long receiptId = JSONUtil.parseObj(release.getEvidenceJson()).getLong("recoveryReceiptId");
        require(receiptId != null && segment.getStartTime() != null
                && segment.getStartTime().isAfter(stop.getStopTime()), "维修复工事件回执或真实切段时刻无效");
        String eventId = "MES-REPAIR-RESUME:" + segment.getId();
        Long outboxId = IdUtil.getSnowflakeNextId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("outboxId", outboxId);
        payload.put("stopId", stop.getId());
        payload.put("releaseId", release.getId());
        payload.put("arrangeId", arrange.getId());
        payload.put("segmentId", segment.getId());
        payload.put("recoveryReceiptId", receiptId);
        payload.put("planNo", task.getPlanNo());
        payload.put("routeCode", arrange.getRouteCode());
        payload.put("deviceCode", stop.getDeviceCode());
        payload.put("resumedTime", segment.getStartTime().toString());
        payload.put("releaseEvidenceFingerprint", release.getEvidenceFingerprint());
        String json = JSONUtil.toJsonStr(payload);
        MesRepairResumeOutbox outbox = new MesRepairResumeOutbox();
        outbox.setId(outboxId);
        outbox.setTenantId(arrange.getTenantId());
        outbox.setFactoryCode(arrange.getFactoryCode());
        outbox.setEventId(eventId);
        outbox.setStopId(stop.getId());
        outbox.setReleaseId(release.getId());
        outbox.setArrangeId(arrange.getId());
        outbox.setSegmentId(segment.getId());
        outbox.setRecoveryReceiptId(receiptId);
        outbox.setPlanNo(task.getPlanNo());
        outbox.setRouteCode(arrange.getRouteCode());
        outbox.setDeviceCode(stop.getDeviceCode());
        outbox.setResumedTime(segment.getStartTime());
        outbox.setPayloadJson(json);
        outbox.setPayloadFingerprint(hash(json));
        outbox.setStatus("PENDING");
        outbox.setAttemptCount(0);
        outbox.setNextAttemptTime(LocalDateTime.now());
        require(outboxes.insert(outbox) == 1, "MES维修真实复工Outbox保存失败");
        record(operations, outbox, "ENQUEUE", null, "PENDING", 0, null, "真实复工切段与Outbox同事务入账");
    }

    @Transactional(readOnly = true)
    public MesRepairResumeEvidence evidence(Long outboxId, String factoryCode) {
        require(outboxId != null && StrUtil.isNotBlank(factoryCode), "维修复工来源查询参数不完整");
        MesRepairResumeOutbox event = outboxes.selectOne(new LambdaQueryWrapper<MesRepairResumeOutbox>()
                .eq(MesRepairResumeOutbox::getId, outboxId)
                .eq(MesRepairResumeOutbox::getFactoryCode, factoryCode).last("LIMIT 1"));
        require(event != null, "MES维修真实复工来源不存在");
        MesRepairArrangeStop stop = stops.selectById(event.getStopId());
        MesRepairProductionRelease release = releases.selectById(event.getReleaseId());
        MesTaskArrangeEntity arrange = arranges.selectById(event.getArrangeId());
        MesTaskExecutionSegmentEntity segment = segments.selectById(event.getSegmentId());
        var payload = JSONUtil.parseObj(event.getPayloadJson());
        boolean valid = stop != null && release != null && arrange != null && segment != null
                && Objects.equals(stop.getTenantId(), event.getTenantId())
                && Objects.equals(release.getTenantId(), event.getTenantId())
                && Objects.equals(arrange.getTenantId(), event.getTenantId())
                && Objects.equals(segment.getTenantId(), event.getTenantId())
                && Objects.equals(stop.getFactoryCode(), factoryCode)
                && Objects.equals(release.getFactoryCode(), factoryCode)
                && Objects.equals(arrange.getFactoryCode(), factoryCode)
                && Objects.equals(segment.getFactoryCode(), factoryCode)
                && Objects.equals(stop.getArrangeId(), arrange.getId())
                && Objects.equals(release.getStopId(), stop.getId())
                && "APPROVE".equals(release.getAction())
                && Objects.equals(segment.getArrangeId(), arrange.getId())
                && !"CANCELLED".equals(segment.getStatus())
                && Objects.equals(segment.getStartTime(), event.getResumedTime())
                && Objects.equals(segment.getProcessCode(), event.getRouteCode())
                && Objects.equals(segment.getDeviceCode(), event.getDeviceCode())
                // 计划号在Outbox入箱时已由数据库校验并冻结；任务归档或改挂不抹掉历史复工事实。
                && StrUtil.isNotBlank(event.getPlanNo())
                && Objects.equals(payload.getLong("outboxId"), event.getId())
                && Objects.equals(payload.getLong("stopId"), event.getStopId())
                && Objects.equals(payload.getLong("releaseId"), event.getReleaseId())
                && Objects.equals(payload.getLong("arrangeId"), event.getArrangeId())
                && Objects.equals(payload.getLong("segmentId"), event.getSegmentId())
                && Objects.equals(payload.getLong("recoveryReceiptId"), event.getRecoveryReceiptId())
                && Objects.equals(payload.getStr("planNo"), event.getPlanNo())
                && Objects.equals(payload.getStr("routeCode"), event.getRouteCode())
                && Objects.equals(payload.getStr("deviceCode"), event.getDeviceCode())
                && Objects.equals(payload.getStr("resumedTime"), event.getResumedTime().toString())
                && Objects.equals(release.getEvidenceFingerprint(),
                payload.getStr("releaseEvidenceFingerprint"))
                && Objects.equals(event.getPayloadFingerprint(), hash(event.getPayloadJson()));
        MesRepairResumeEvidence result = new MesRepairResumeEvidence();
        result.setTenantId(event.getTenantId());
        result.setFactoryCode(factoryCode);
        result.setOutboxId(event.getId());
        result.setEventId(event.getEventId());
        result.setStopId(event.getStopId());
        result.setReleaseId(event.getReleaseId());
        result.setArrangeId(event.getArrangeId());
        result.setSegmentId(event.getSegmentId());
        result.setRecoveryReceiptId(event.getRecoveryReceiptId());
        result.setPlanNo(event.getPlanNo());
        result.setRouteCode(event.getRouteCode());
        result.setDeviceCode(event.getDeviceCode());
        result.setResumedTime(event.getResumedTime());
        result.setPayloadFingerprint(event.getPayloadFingerprint());
        result.setSourceValid(valid);
        return result;
    }

    @Transactional(readOnly = true)
    public Page<MesRepairResumeOutbox> page(long current, long size, String status) {
        String factory = access("read");
        require(current >= 1 && size >= 1 && size <= 100, "分页参数无效");
        return outboxes.selectPage(new Page<>(current, size), new LambdaQueryWrapper<MesRepairResumeOutbox>()
                .eq(MesRepairResumeOutbox::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesRepairResumeOutbox::getFactoryCode, factory)
                .eq(StrUtil.isNotBlank(status), MesRepairResumeOutbox::getStatus, status)
                .orderByDesc(MesRepairResumeOutbox::getId));
    }

    @Transactional(readOnly = true)
    public MesRepairResumeOutbox byStop(Long stopId, String factory) {
        require(stopId != null && TenantContextHolder.getTenantId() != null && StrUtil.isNotBlank(factory),
                "停工复工回写查询参数无效");
        return outboxes.selectOne(new LambdaQueryWrapper<MesRepairResumeOutbox>()
                .eq(MesRepairResumeOutbox::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesRepairResumeOutbox::getFactoryCode, factory)
                .eq(MesRepairResumeOutbox::getStopId, stopId).last("LIMIT 1"));
    }

    @Transactional(readOnly = true)
    public List<MesRepairResumeDeliveryOperation> history(Long outboxId, String factory) {
        if (outboxId == null) return List.of();
        return operations.selectList(new LambdaQueryWrapper<MesRepairResumeDeliveryOperation>()
                .eq(MesRepairResumeDeliveryOperation::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesRepairResumeDeliveryOperation::getFactoryCode, factory)
                .eq(MesRepairResumeDeliveryOperation::getOutboxId, outboxId)
                .orderByAsc(MesRepairResumeDeliveryOperation::getId).last("LIMIT 100"));
    }

    @Transactional(rollbackFor = Exception.class)
    public MesRepairResumeOutbox retry(Long id, String requestId) {
        String factory = access("write");
        require(id != null && requestId != null && requestId.matches("[A-Za-z0-9_-]{16,64}"), "重试请求号无效");
        MesRepairResumeOutbox row = outboxes.selectOne(new LambdaQueryWrapper<MesRepairResumeOutbox>()
                .eq(MesRepairResumeOutbox::getId, id)
                .eq(MesRepairResumeOutbox::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesRepairResumeOutbox::getFactoryCode, factory).last("FOR UPDATE"));
        require(row != null, "当前工厂复工投递不存在");
        if (Objects.equals(row.getRetryRequestId(), requestId)) return row;
        require("DEAD_LETTER".equals(row.getStatus()), "仅死信的维修复工投递允许人工重试");
        row.setRetryRequestId(requestId);
        row.setStatus("RETRY_WAIT");
        row.setAttemptCount(0);
        row.setNextAttemptTime(LocalDateTime.now());
        row.setLastError(null);
        require(outboxes.updateById(row) == 1, "维修复工死信重试保存失败");
        record(operations, row, "MANUAL_RETRY", "DEAD_LETTER", "RETRY_WAIT", 0, requestId,
                "人工核对PLAN收件状态后沿原事件ID续投");
        return row;
    }

    public int dispatchDue(int limit) {
        var rows = outboxes.selectPage(new Page<>(1, Math.max(1, Math.min(limit, 200))),
                new LambdaQueryWrapper<MesRepairResumeOutbox>()
                        .in(MesRepairResumeOutbox::getStatus, "PENDING", "RETRY_WAIT")
                        .le(MesRepairResumeOutbox::getNextAttemptTime, LocalDateTime.now())
                        .orderByAsc(MesRepairResumeOutbox::getNextAttemptTime)
                        .orderByAsc(MesRepairResumeOutbox::getId)).getRecords();
        int done = 0;
        for (var row : rows) {
            try {
                boolean sent = row.getTenantId() == null ? worker.deliverOne(row.getId())
                        : TenantBroker.applyAs(row.getTenantId(), ignored -> worker.deliverOne(row.getId()));
                if (sent) done++;
            } catch (RuntimeException ex) {
                try {
                    if (row.getTenantId() == null) failures.mark(row.getId(), ex);
                    else TenantBroker.applyAs(row.getTenantId(), ignored -> {
                        failures.mark(row.getId(), ex);
                        return null;
                    });
                } catch (RuntimeException failure) {
                    log.error("维修复工事件投递失败账保存异常，outboxId={}", row.getId(), failure);
                }
            }
        }
        return done;
    }

    private String access(String level) {
        var user = SecurityUtils.getUser();
        String factory = FactoryContextHolder.getFactoryCode();
        require(user != null && user.getId() != null && TenantContextHolder.getTenantId() != null
                && !TenantContextHolder.getTenantSkip() && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                && StrUtil.isNotBlank(factory), "维修复工事件须实名选择实际工厂");
        var grant = factories.check(new MdmFactoryAccessCheck().setUserId(user.getId())
                .setFactoryCode(factory).setRequiredPermission(level));
        require(grant != null && grant.isOk() && Boolean.TRUE.equals(grant.getData()), "维修复工事件实际工厂权限不足");
        return factory;
    }
}
