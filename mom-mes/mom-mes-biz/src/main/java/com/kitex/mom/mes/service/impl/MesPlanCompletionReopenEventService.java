package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesPlanCompletionReopenEvidence;
import com.kitex.mom.mes.entity.MesPlanCompletionReopenOperationEntity;
import com.kitex.mom.mes.entity.MesPlanCompletionReopenOutboxEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesPlanCompletionMapper;
import com.kitex.mom.mes.mapper.MesPlanCompletionReopenOperationMapper;
import com.kitex.mom.mes.mapper.MesPlanCompletionReopenOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 报工冲销重新打开MES完工工单后，形成并投递PLAN负向完成事实。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MesPlanCompletionReopenEventService {

    private final MesPlanCompletionReopenOutboxMapper outboxMapper;
    private final MesPlanCompletionReopenOperationMapper operationMapper;
    private final MesActualFactoryAccessService factoryAccess;
    private final MesPlanCompletionMapper completionFacts;
    private final MesPlanCompletionOutboxService completionSources;
    private final ObjectMapper json;
    private final DataSource dataSource;
    private final MesPlanCompletionReopenEventWorker worker;
    private final MesPlanCompletionReopenEventFailureService failureService;

    static void record(
            MesPlanCompletionReopenOperationMapper mapper,
            MesPlanCompletionReopenOutboxEntity outbox,
            String action,
            String before,
            String after,
            int attempt,
            String requestId,
            String detail) {
        MesPlanCompletionReopenOperationEntity operation =
                new MesPlanCompletionReopenOperationEntity()
                        .setOutboxId(outbox.getId())
                        .setAction(action)
                        .setAttemptNo(attempt)
                        .setBeforeStatus(before)
                        .setAfterStatus(after)
                        .setRequestId(requestId)
                        .setDetail(StrUtil.sub(detail, 0, 500))
                        .setOperationTime(LocalDateTime.now())
                        .setFactoryCode(outbox.getFactoryCode());
        operation.setTenantId(outbox.getTenantId());
        require(mapper.insert(operation) == 1, "MES完工重新打开事件履历保存失败");
    }

    static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("生成完工重新打开事件指纹失败", exception);
        }
    }

    private static BigDecimal zero(BigDecimal value) {
        return Objects.requireNonNullElse(value, BigDecimal.ZERO);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    /**
     * 与报工冲销批准处于同一事务：重算APS来源完成量并写入可靠Outbox。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public MesPlanCompletionReopenOutboxEntity enqueue(
            MesTaskEntity before, MesTaskEntity after, Long reverseRequestId) {
        require(
                before != null
                        && after != null
                        && before.getId() != null
                        && Objects.equals(before.getId(), after.getId()),
                "完工重新打开事件缺少同一工单前后事实");
        if (!"FINISH".equals(before.getStatus())
                || "FINISH".equals(after.getStatus())
                || StrUtil.isBlank(after.getPlanNo())) return null;
        Long tenant = MesPlanCompletionOutboxService.scope(after.getFactoryCode());
        requireTransaction();
        require(
                reverseRequestId != null
                        && reverseRequestId > 0
                        && Objects.equals(before.getTenantId(), tenant)
                        && Objects.equals(after.getTenantId(), tenant)
                        && Objects.equals(before.getFactoryCode(), after.getFactoryCode())
                        && Objects.equals(before.getCode(), after.getCode())
                        && Objects.equals(before.getPlanNo(), after.getPlanNo())
                        && Objects.equals(before.getMatCode(), after.getMatCode()),
                "冲销前后组织或稳定工单来源不一致");
        completionFacts.lockEnqueue(
                "MES_COMPLETION_REOPEN:"
                        + tenant
                        + ":"
                        + after.getFactoryCode()
                        + ":"
                        + reverseRequestId
                        + ":"
                        + after.getId());
        int beforeVersion = Objects.requireNonNullElse(before.getCompletionVersion(), 0);
        var current = completionFacts.task(tenant, after.getFactoryCode(), after.getId());
        require(
                current != null
                        && Objects.equals(current.getCode(), after.getCode())
                        && Objects.equals(current.getMatCode(), after.getMatCode())
                        && Objects.equals(current.getPlanNo(), after.getPlanNo())
                        && "PRODUCT".equals(current.getStatus())
                        && current.getFinishTime() == null
                        && MesPlanCompletionOutboxService.same(
                        current.getCompleteNum(), after.getCompleteNum())
                        && MesPlanCompletionOutboxService.same(
                        current.getNoticeNum(), before.getNoticeNum())
                        && Objects.equals(
                        current.getCompletionVersion(), after.getCompletionVersion())
                        && beforeVersion >= 0
                        && after.getCompletionVersion() != null
                        && (long) after.getCompletionVersion() == (long) beforeVersion + 1
                        && before.getCompleteNum() != null
                        && current.getCompleteNum() != null
                        && current.getCompleteNum().signum() >= 0
                        && before.getCompleteNum().compareTo(current.getCompleteNum()) > 0
                        && before.getCompleteNum().compareTo(current.getNoticeNum()) <= 0,
                "反向完成必须绑定同事务已保存的重新打开工单、减少量和新世代");
        if (MesPlanCompletionOutboxService.rework(current)) return null;
        var request = outboxMapper.request(tenant, current.getFactoryCode(), reverseRequestId);
        require(
                request != null
                        && Objects.equals(request.getTaskCode(), current.getCode())
                        && List.of("SUBMITTED", "APPROVED").contains(request.getStatus()),
                "反向完成缺少同工单实际冲销申请");
        var originals =
                outboxMapper.originals(
                        tenant, current.getFactoryCode(), reverseRequestId, current.getId());
        require(originals != null && originals.size() <= 1, "反向完成原件重复或不可读取");
        if (!originals.isEmpty()) {
            var original = originals.get(0);
            MesCompletionReopenCodec.read(json, original);
            require(
                    Objects.equals(original.getTaskCode(), current.getCode())
                            && Objects.equals(
                            original.getCompletionVersionAfter(),
                            current.getCompletionVersion())
                            && MesPlanCompletionOutboxService.same(
                            original.getCompleteQuantityBefore(), before.getCompleteNum())
                            && MesPlanCompletionOutboxService.same(
                            original.getCompleteQuantityAfter(), current.getCompleteNum()),
                    "同一批准冲销原号不能换世代或数量");
            return original;
        }
        var lines =
                completionSources.sourceLines(current, true).stream()
                        .map(
                                line ->
                                        new MesPlanCompletionReopenEvidence.SourceLine()
                                                .setIssueId(line.getIssueId())
                                                .setIssueNo(line.getIssueNo())
                                                .setAllocatedQuantity(line.getAllocatedQuantity())
                                                .setCompletedQuantity(line.getCompletedQuantity()))
                        .toList();
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        Long id = IdUtil.getSnowflakeNextId();
        String eventId =
                "MES-COMPLETION-REOPEN:" + reverseRequestId + ":V" + current.getCompletionVersion();
        var payload =
                new MesPlanCompletionReopenEvidence()
                        .setSourceSchemaVersion(2)
                        .setTenantId(tenant)
                        .setFactoryCode(current.getFactoryCode())
                        .setOutboxId(id)
                        .setEventId(eventId)
                        .setReverseRequestId(reverseRequestId)
                        .setTaskId(current.getId())
                        .setTaskCode(current.getCode())
                        .setPlanNo(current.getPlanNo())
                        .setApsBatchNo(StrUtil.trimToNull(current.getApsBatchNo()))
                        .setProductCode(current.getMatCode())
                        .setCompleteQuantityBefore(before.getCompleteNum())
                        .setCompleteQuantityAfter(current.getCompleteNum())
                        .setCompletionVersionBefore(beforeVersion)
                        .setCompletionVersionAfter(current.getCompletionVersion())
                        .setReopenedTime(now)
                        .setSourceLines(lines);
        String raw = MesPlanCompletionCodec.encode(json, payload);
        var row =
                new MesPlanCompletionReopenOutboxEntity()
                        .setEventId(eventId)
                        .setReverseRequestId(reverseRequestId)
                        .setTaskId(current.getId())
                        .setTaskCode(current.getCode())
                        .setPlanNo(current.getPlanNo())
                        .setApsBatchNo(current.getApsBatchNo())
                        .setProductCode(current.getMatCode())
                        .setCompleteQuantityBefore(before.getCompleteNum())
                        .setCompleteQuantityAfter(current.getCompleteNum())
                        .setCompletionVersionBefore(beforeVersion)
                        .setCompletionVersionAfter(current.getCompletionVersion())
                        .setReopenedTime(now)
                        .setPayloadJson(raw)
                        .setPayloadFingerprint(hash(raw))
                        .setStatus("PENDING")
                        .setAttemptCount(0)
                        .setNextAttemptTime(now)
                        .setFactoryCode(current.getFactoryCode());
        row.setId(id);
        row.setTenantId(tenant);
        MesCompletionReopenCodec.read(json, row);
        require(outboxMapper.insert(row) == 1, "MES完工重新打开Outbox保存失败");
        record(
                operationMapper,
                row,
                "ENQUEUE",
                null,
                "PENDING",
                0,
                null,
                "批准冲销与本次工单逐来源反向完成原件同事务保存");
        return row;
    }

    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public MesPlanCompletionReopenEvidence evidence(Long outboxId, String factoryCode) {
        Long tenant = MesPlanCompletionOutboxService.scope(factoryCode);
        require(outboxId != null && outboxId > 0, "完工重新打开原件主键无效");
        var row = outboxMapper.scoped(tenant, factoryCode, outboxId);
        require(row != null, "MES完工重新打开来源事件不存在");
        var payload = MesCompletionReopenCodec.read(json, row);
        var request = outboxMapper.request(tenant, factoryCode, row.getReverseRequestId());
        var task = completionFacts.task(tenant, factoryCode, row.getTaskId());
        boolean valid =
                request != null
                        && "APPROVED".equals(request.getStatus())
                        && Objects.equals(request.getTaskCode(), row.getTaskCode())
                        && task != null
                        && !MesPlanCompletionOutboxService.rework(task)
                        && Objects.equals(task.getCode(), row.getTaskCode())
                        && Objects.equals(task.getPlanNo(), row.getPlanNo())
                        && Objects.equals(task.getMatCode(), row.getProductCode())
                        && Objects.equals(
                        StrUtil.trimToNull(task.getApsBatchNo()),
                        StrUtil.trimToNull(row.getApsBatchNo()))
                        && Objects.requireNonNullElse(task.getCompletionVersion(), 0)
                        >= row.getCompletionVersionAfter();
        if (valid && MesCompletionReopenCodec.version(payload) == 2) {
            var current = completionSources.sourceLines(task, false);
            var frozen = payload.getSourceLines();
            require(current.size() == frozen.size(), "反向完成原来源集合已改变");
            for (int i = 0; i < current.size(); i++)
                require(
                        Objects.equals(current.get(i).getIssueId(), frozen.get(i).getIssueId())
                                && Objects.equals(
                                current.get(i).getIssueNo(), frozen.get(i).getIssueNo())
                                && MesPlanCompletionOutboxService.same(
                                current.get(i).getAllocatedQuantity(),
                                frozen.get(i).getAllocatedQuantity()),
                        "反向完成原来源分配已改变");
        }
        return payload.setPayloadFingerprint(row.getPayloadFingerprint()).setSourceValid(valid);
    }

    private void requireTransaction() {
        require(TransactionSynchronizationManager.isActualTransactionActive(), "反向完成须与批准冲销同事务");
        var c = DataSourceUtils.getConnection(dataSource);
        try {
            require(
                    DataSourceUtils.isConnectionTransactional(c, dataSource)
                            && c.getTransactionIsolation() == Connection.TRANSACTION_READ_COMMITTED,
                    "反向完成需要同数据源RC事务");
        } catch (SQLException failure) {
            throw new CheckedException("无法核验反向完成事务");
        } finally {
            DataSourceUtils.releaseConnection(c, dataSource);
        }
    }

    @Transactional(readOnly = true)
    public Page<MesPlanCompletionReopenOutboxEntity> page(long current, long size, String status) {
        String factory = factoryAccess.require("read");
        Long tenant = MesPlanCompletionOutboxService.scope(factory);
        require(current > 0 && size > 0 && size <= 100, "分页参数无效");
        return outboxMapper.selectPage(
                new Page<>(current, size),
                new LambdaQueryWrapper<MesPlanCompletionReopenOutboxEntity>()
                        .eq(MesPlanCompletionReopenOutboxEntity::getTenantId, tenant)
                        .eq(MesPlanCompletionReopenOutboxEntity::getFactoryCode, factory)
                        .eq(
                                StrUtil.isNotBlank(status),
                                MesPlanCompletionReopenOutboxEntity::getStatus,
                                status)
                        .orderByDesc(MesPlanCompletionReopenOutboxEntity::getId));
    }

    /**
     * 查看单笔PLAN负向完成事件及最新投递状态。
     */
    @Transactional(readOnly = true)
    public MesPlanCompletionReopenOutboxEntity detail(Long id) {
        String factory = factoryAccess.require("read");
        Long tenant = MesPlanCompletionOutboxService.scope(factory);
        require(id != null, "完工重新打开事件主键不能为空");
        MesPlanCompletionReopenOutboxEntity row =
                outboxMapper.selectOne(
                        new LambdaQueryWrapper<MesPlanCompletionReopenOutboxEntity>()
                                .eq(MesPlanCompletionReopenOutboxEntity::getId, id)
                                .eq(MesPlanCompletionReopenOutboxEntity::getTenantId, tenant)
                                .eq(MesPlanCompletionReopenOutboxEntity::getFactoryCode, factory),
                        false);
        require(row != null, "当前工厂完工重新打开事件不存在");
        return row;
    }

    @Transactional(rollbackFor = Exception.class)
    public MesPlanCompletionReopenOutboxEntity retry(Long id, String requestId) {
        String factory = factoryAccess.require("write");
        Long tenant = MesPlanCompletionOutboxService.scope(factory);
        require(
                id != null
                        && id > 0
                        && requestId != null
                        && requestId.matches("[A-Za-z0-9_-]{16,64}"),
                "重试请求号无效");
        // 交互入口保留原人员数据范围；只有通过授权的行才使用局部状态更新。
        var row =
                outboxMapper.selectOne(
                        new LambdaQueryWrapper<MesPlanCompletionReopenOutboxEntity>()
                                .eq(MesPlanCompletionReopenOutboxEntity::getTenantId, tenant)
                                .eq(MesPlanCompletionReopenOutboxEntity::getFactoryCode, factory)
                                .eq(MesPlanCompletionReopenOutboxEntity::getId, id)
                                .last("FOR UPDATE"));
        require(row != null, "当前范围反向完成事件不存在");
        var previous = operationMapper.request(tenant, requestId);
        require(previous != null && previous.size() <= 1, "人工续投原请求履历重复或不可读取");
        if (!previous.isEmpty()) {
            var original = previous.get(0);
            require(
                    Objects.equals(original.getFactoryCode(), factory)
                            && Objects.equals(original.getOutboxId(), id)
                            && "MANUAL_RETRY".equals(original.getAction()),
                    "人工续投请求号已用于其他原件");
            return row;
        }
        require("DEAD_LETTER".equals(row.getStatus()), "只有死信事件允许人工续投");
        // 历史重试号已保存在原行却缺原履历时，不补造一次新续投。
        require(!Objects.equals(row.getRetryRequestId(), requestId), "人工续投原号缺少永久履历，不能重新应用");
        MesCompletionReopenCodec.read(json, row);
        require(
                outboxMapper.state(
                        tenant,
                        factory,
                        id,
                        "DEAD_LETTER",
                        "RETRY_WAIT",
                        0,
                        LocalDateTime.now(),
                        row.getDeliveredTime(),
                        row.getPlanInboxId(),
                        row.getPlanEventNo(),
                        row.getPlanEventStatus(),
                        null,
                        requestId)
                        == 1,
                "人工续投保存失败");
        record(
                operationMapper,
                row,
                "MANUAL_RETRY",
                "DEAD_LETTER",
                "RETRY_WAIT",
                0,
                requestId,
                "人工核对后按永久原事件续投，不代表PLAN已执行");
        row.setStatus("RETRY_WAIT")
                .setAttemptCount(0)
                .setNextAttemptTime(LocalDateTime.now())
                .setLastError(null)
                .setRetryRequestId(requestId);
        return row;
    }

    /**
     * 仅供后台作业扫描；按真实行逐笔进入组织，不借用当前用户租户范围。
     */
    public int dispatchDue(int limit) {
        int delivered = 0;
        for (var row : outboxMapper.due(Math.max(1, Math.min(limit, 200)))) {
            if (row.getTenantId() == null
                    || row.getTenantId() <= 0
                    || StrUtil.isBlank(row.getFactoryCode())) {
                log.warn("反向完成原件缺实际组织，outboxId={}", row.getId());
                continue;
            }
            Long oldTenant = TenantContextHolder.getTenantId();
            boolean oldTenantSkip = TenantContextHolder.getTenantSkip();
            String oldFactory = FactoryContextHolder.getFactoryCode();
            boolean oldFactorySkip = Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip());
            try {
                TenantContextHolder.clear();
                FactoryContextHolder.clear();
                boolean accepted =
                        TenantBroker.applyAs(
                                row.getTenantId(),
                                ignored -> {
                                    FactoryContextHolder.setFactoryCode(row.getFactoryCode());
                                    try {
                                        return worker.deliverOne(row.getId());
                                    } catch (RuntimeException failure) {
                                        failureService.mark(row.getId(), failure);
                                        return false;
                                    }
                                });
                if (accepted) delivered++;
            } catch (RuntimeException failure) {
                log.warn(
                        "反向完成投递或失败账未完成，outboxId={}，errorType={}",
                        row.getId(),
                        failure.getClass().getSimpleName());
            } finally {
                TenantContextHolder.clear();
                FactoryContextHolder.clear();
                TenantContextHolder.setTenantId(oldTenant);
                FactoryContextHolder.setFactoryCode(oldFactory);
                if (oldTenantSkip) TenantContextHolder.setTenantSkip();
                if (oldFactorySkip) FactoryContextHolder.setFactorySkip();
            }
        }
        return delivered;
    }
}
