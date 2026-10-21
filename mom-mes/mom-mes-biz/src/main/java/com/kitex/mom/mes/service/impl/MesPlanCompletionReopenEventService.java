package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.mom.mes.dto.MesPlanCompletionReopenEvidence;
import com.kitex.mom.mes.entity.MesApsBatchAllocationEntity;
import com.kitex.mom.mes.entity.MesPlanCompletionReopenOperationEntity;
import com.kitex.mom.mes.entity.MesPlanCompletionReopenOutboxEntity;
import com.kitex.mom.mes.entity.MesReportReverseRequestEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesApsBatchAllocationMapper;
import com.kitex.mom.mes.mapper.MesPlanCompletionReopenOperationMapper;
import com.kitex.mom.mes.mapper.MesPlanCompletionReopenOutboxMapper;
import com.kitex.mom.mes.mapper.MesReportReverseRequestMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 报工冲销重新打开MES完工工单后，形成并投递PLAN负向完成事实。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MesPlanCompletionReopenEventService {

    private final MesPlanCompletionReopenOutboxMapper outboxMapper;
    private final MesPlanCompletionReopenOperationMapper operationMapper;
    private final MesApsBatchAllocationMapper allocationMapper;
    private final MesTaskMapper taskMapper;
    private final MesReportReverseRequestMapper reverseRequestMapper;
    private final MesActualFactoryAccessService factoryAccess;
    private final MesPlanCompletionReopenEventWorker worker;
    private final MesPlanCompletionReopenEventFailureService failureService;

    /**
     * 与报工冲销批准处于同一事务：重算APS来源完成量并写入可靠Outbox。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public MesPlanCompletionReopenOutboxEntity enqueue(MesTaskEntity before, MesTaskEntity after,
                                                        Long reverseRequestId) {
        require(before != null && after != null && before.getId() != null
                        && Objects.equals(before.getId(), after.getId()), "完工重新打开事件缺少同一工单前后事实");
        if (!"FINISH".equals(before.getStatus()) || "FINISH".equals(after.getStatus())
                || StrUtil.isBlank(after.getPlanNo())) {
            return null;
        }
        require(reverseRequestId != null && StrUtil.isNotBlank(after.getFactoryCode())
                        && Objects.equals(before.getPlanNo(), after.getPlanNo()),
                "完工重新打开事件缺少冲销申请、工厂或稳定PLAN来源");
        MesPlanCompletionReopenOutboxEntity duplicate = outboxMapper.selectOne(
                new LambdaQueryWrapper<MesPlanCompletionReopenOutboxEntity>()
                        .eq(MesPlanCompletionReopenOutboxEntity::getReverseRequestId, reverseRequestId)
                        .eq(MesPlanCompletionReopenOutboxEntity::getTaskId, after.getId()), false);
        if (duplicate != null) return duplicate;

        List<MesPlanCompletionReopenEvidence.SourceLine> lines = sourceLines(after);
        if (lines.isEmpty()) return null;
        LocalDateTime now = LocalDateTime.now();
        Long outboxId = IdUtil.getSnowflakeNextId();
        String eventId = "MES-COMPLETION-REOPEN:" + reverseRequestId + ":V"
                + Objects.requireNonNullElse(after.getCompletionVersion(), 0);
        MesPlanCompletionReopenEvidence payload = new MesPlanCompletionReopenEvidence()
                .setTenantId(after.getTenantId()).setFactoryCode(after.getFactoryCode())
                .setOutboxId(outboxId).setEventId(eventId).setReverseRequestId(reverseRequestId)
                .setTaskId(after.getId()).setTaskCode(after.getCode()).setPlanNo(after.getPlanNo())
                .setApsBatchNo(after.getApsBatchNo()).setProductCode(after.getMatCode())
                .setCompleteQuantityBefore(zero(before.getCompleteNum()))
                .setCompleteQuantityAfter(zero(after.getCompleteNum()))
                .setCompletionVersionBefore(Objects.requireNonNullElse(before.getCompletionVersion(), 0))
                .setCompletionVersionAfter(Objects.requireNonNullElse(after.getCompletionVersion(), 0))
                .setReopenedTime(now).setSourceLines(lines);
        String json = JSONUtil.toJsonStr(payload);
        MesPlanCompletionReopenOutboxEntity outbox = new MesPlanCompletionReopenOutboxEntity()
                .setEventId(eventId).setReverseRequestId(reverseRequestId).setTaskId(after.getId())
                .setTaskCode(after.getCode()).setPlanNo(after.getPlanNo()).setApsBatchNo(after.getApsBatchNo())
                .setProductCode(after.getMatCode()).setCompleteQuantityBefore(zero(before.getCompleteNum()))
                .setCompleteQuantityAfter(zero(after.getCompleteNum()))
                .setCompletionVersionBefore(payload.getCompletionVersionBefore())
                .setCompletionVersionAfter(payload.getCompletionVersionAfter()).setReopenedTime(now)
                .setPayloadJson(json).setPayloadFingerprint(hash(json)).setStatus("PENDING")
                .setAttemptCount(0).setNextAttemptTime(now).setFactoryCode(after.getFactoryCode());
        outbox.setId(outboxId);
        outbox.setTenantId(after.getTenantId());
        require(outboxMapper.insert(outbox) == 1, "MES完工重新打开Outbox保存失败");
        record(operationMapper, outbox, "ENQUEUE", null, "PENDING", 0, null,
                "报工冲销、来源完成量重算与PLAN负向完成事件同事务入账");
        return outbox;
    }

    private List<MesPlanCompletionReopenEvidence.SourceLine> sourceLines(MesTaskEntity task) {
        if (StrUtil.isBlank(task.getApsBatchNo())) return legacySourceLines(task);
        taskMapper.acquireApsBatchTransactionLock("PLAN_COMPLETION_REOPEN:" + task.getApsBatchNo());
        List<MesApsBatchAllocationEntity> taskRows = allocationMapper.selectList(
                new LambdaQueryWrapper<MesApsBatchAllocationEntity>()
                        .eq(MesApsBatchAllocationEntity::getMesTaskId, task.getId())
                        .orderByAsc(MesApsBatchAllocationEntity::getAllocationSeq).last("FOR UPDATE"));
        require(!taskRows.isEmpty(), "APS批次工单缺少来源分配账：" + task.getApsBatchNo());
        BigDecimal taskAllocated = taskRows.stream().map(MesApsBatchAllocationEntity::getAllocatedQuantity)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal completed = zero(task.getCompleteNum());
        require(task.getNoticeNum() != null && taskAllocated.compareTo(task.getNoticeNum()) == 0
                        && completed.signum() >= 0 && completed.compareTo(taskAllocated) <= 0,
                "APS来源分配量、工单数量和冲销后完成量不守恒");
        BigDecimal assigned = BigDecimal.ZERO;
        for (int index = 0; index < taskRows.size(); index++) {
            MesApsBatchAllocationEntity row = taskRows.get(index);
            BigDecimal target = index == taskRows.size() - 1 ? completed.subtract(assigned)
                    : completed.multiply(row.getAllocatedQuantity())
                    .divide(taskAllocated, 8, RoundingMode.HALF_UP).min(row.getAllocatedQuantity());
            assigned = assigned.add(target);
            row.setCompletedQuantity(target);
            require(allocationMapper.updateById(row) == 1, "APS来源完成量重算失败：" + row.getIssueNo());
        }
        Set<Long> issueIds = taskRows.stream().map(MesApsBatchAllocationEntity::getIssueId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        require(issueIds.size() == taskRows.stream().map(MesApsBatchAllocationEntity::getIssueId)
                .distinct().count(), "APS来源分配存在缺失计划主键，不能可靠回写PLAN");
        List<MesApsBatchAllocationEntity> allRows = allocationMapper.selectList(
                new LambdaQueryWrapper<MesApsBatchAllocationEntity>()
                        .in(MesApsBatchAllocationEntity::getIssueId, issueIds)
                        .eq(MesApsBatchAllocationEntity::getFactoryCode, task.getFactoryCode())
                        .orderByAsc(MesApsBatchAllocationEntity::getIssueId)
                        .orderByAsc(MesApsBatchAllocationEntity::getId).last("FOR UPDATE"));
        Map<Long, List<MesApsBatchAllocationEntity>> grouped = allRows.stream().collect(
                Collectors.groupingBy(MesApsBatchAllocationEntity::getIssueId, LinkedHashMap::new, Collectors.toList()));
        List<MesPlanCompletionReopenEvidence.SourceLine> result = new ArrayList<>();
        for (Long issueId : issueIds) {
            List<MesApsBatchAllocationEntity> rows = grouped.getOrDefault(issueId, List.of());
            require(!rows.isEmpty(), "APS来源计划聚合账缺失：" + issueId);
            String issueNo = rows.get(0).getIssueNo();
            require(StrUtil.isNotBlank(issueNo) && rows.stream().allMatch(row -> Objects.equals(issueNo, row.getIssueNo())),
                    "APS来源计划编号不一致：" + issueId);
            BigDecimal allocated = rows.stream().map(MesApsBatchAllocationEntity::getAllocatedQuantity)
                    .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal finished = rows.stream().map(MesApsBatchAllocationEntity::getCompletedQuantity)
                    .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (finished.compareTo(allocated) < 0) {
                result.add(new MesPlanCompletionReopenEvidence.SourceLine().setIssueId(issueId)
                        .setIssueNo(issueNo).setAllocatedQuantity(allocated).setCompletedQuantity(finished));
            }
        }
        return List.copyOf(result);
    }

    private List<MesPlanCompletionReopenEvidence.SourceLine> legacySourceLines(MesTaskEntity task) {
        List<MesTaskEntity> related = taskMapper.selectList(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getFactoryCode, task.getFactoryCode())
                .eq(MesTaskEntity::getPlanNo, task.getPlanNo())
                .eq(MesTaskEntity::getMatCode, task.getMatCode()).orderByAsc(MesTaskEntity::getId)
                .last("FOR UPDATE"));
        require(!related.isEmpty(), "PLAN来源工单聚合范围为空：" + task.getPlanNo());
        BigDecimal allocated = related.stream().map(MesTaskEntity::getNoticeNum)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal completed = related.stream().map(MesTaskEntity::getCompleteNum)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        require(allocated.signum() > 0 && completed.signum() >= 0, "PLAN来源工单数量不合法");
        if (completed.compareTo(allocated) >= 0) return List.of();
        return List.of(new MesPlanCompletionReopenEvidence.SourceLine().setIssueNo(task.getPlanNo())
                .setAllocatedQuantity(allocated).setCompletedQuantity(completed));
    }

    @Transactional(readOnly = true)
    public MesPlanCompletionReopenEvidence evidence(Long outboxId, String factoryCode) {
        require(outboxId != null && StrUtil.isNotBlank(factoryCode), "完工重新打开来源查询参数不完整");
        MesPlanCompletionReopenOutboxEntity outbox = outboxMapper.selectOne(
                new LambdaQueryWrapper<MesPlanCompletionReopenOutboxEntity>()
                        .eq(MesPlanCompletionReopenOutboxEntity::getId, outboxId)
                        .eq(MesPlanCompletionReopenOutboxEntity::getFactoryCode, factoryCode), false);
        require(outbox != null, "MES完工重新打开来源事件不存在");
        MesPlanCompletionReopenEvidence result = JSONUtil.toBean(outbox.getPayloadJson(),
                MesPlanCompletionReopenEvidence.class);
        MesReportReverseRequestEntity request = reverseRequestMapper.selectById(outbox.getReverseRequestId());
        MesTaskEntity task = taskMapper.selectById(outbox.getTaskId());
        boolean valid = request != null && "APPROVED".equals(request.getStatus()) && task != null
                && Objects.equals(request.getTenantId(), outbox.getTenantId())
                && Objects.equals(task.getTenantId(), outbox.getTenantId())
                && Objects.equals(request.getFactoryCode(), factoryCode)
                && Objects.equals(task.getFactoryCode(), factoryCode)
                && Objects.equals(task.getCode(), outbox.getTaskCode())
                && Objects.equals(task.getPlanNo(), outbox.getPlanNo())
                && Objects.equals(result.getOutboxId(), outbox.getId())
                && Objects.equals(result.getEventId(), outbox.getEventId())
                && Objects.equals(result.getReverseRequestId(), outbox.getReverseRequestId())
                && Objects.equals(result.getTaskId(), outbox.getTaskId())
                && Objects.equals(result.getCompletionVersionAfter(), outbox.getCompletionVersionAfter())
                && Objects.requireNonNullElse(task.getCompletionVersion(), 0) >= outbox.getCompletionVersionAfter()
                && Objects.equals(hash(outbox.getPayloadJson()), outbox.getPayloadFingerprint());
        return result.setPayloadFingerprint(outbox.getPayloadFingerprint()).setSourceValid(valid);
    }

    @Transactional(readOnly = true)
    public Page<MesPlanCompletionReopenOutboxEntity> page(long current, long size, String status) {
        String factory = factoryAccess.require("read");
        require(current > 0 && size > 0 && size <= 100, "分页参数无效");
        return outboxMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<MesPlanCompletionReopenOutboxEntity>()
                        .eq(MesPlanCompletionReopenOutboxEntity::getFactoryCode, factory)
                        .eq(StrUtil.isNotBlank(status), MesPlanCompletionReopenOutboxEntity::getStatus, status)
                        .orderByDesc(MesPlanCompletionReopenOutboxEntity::getId));
    }

    /**
     * 查看单笔PLAN负向完成事件及最新投递状态。
     */
    @Transactional(readOnly = true)
    public MesPlanCompletionReopenOutboxEntity detail(Long id) {
        String factory = factoryAccess.require("read");
        require(id != null, "完工重新打开事件主键不能为空");
        MesPlanCompletionReopenOutboxEntity row = outboxMapper.selectOne(
                new LambdaQueryWrapper<MesPlanCompletionReopenOutboxEntity>()
                        .eq(MesPlanCompletionReopenOutboxEntity::getId, id)
                        .eq(MesPlanCompletionReopenOutboxEntity::getFactoryCode, factory), false);
        require(row != null, "当前工厂完工重新打开事件不存在");
        return row;
    }

    @Transactional(rollbackFor = Exception.class)
    public MesPlanCompletionReopenOutboxEntity retry(Long id, String requestId) {
        String factory = factoryAccess.require("write");
        require(id != null && requestId != null && requestId.matches("[A-Za-z0-9_-]{16,64}"), "重试请求号无效");
        MesPlanCompletionReopenOutboxEntity row = outboxMapper.selectOne(
                new LambdaQueryWrapper<MesPlanCompletionReopenOutboxEntity>()
                        .eq(MesPlanCompletionReopenOutboxEntity::getId, id)
                        .eq(MesPlanCompletionReopenOutboxEntity::getFactoryCode, factory).last("FOR UPDATE"));
        require(row != null, "当前工厂完工重新打开投递不存在");
        if (Objects.equals(row.getRetryRequestId(), requestId)) return row;
        require("DEAD_LETTER".equals(row.getStatus()), "只有死信事件允许人工续投");
        String before = row.getStatus();
        row.setRetryRequestId(requestId).setStatus("RETRY_WAIT").setAttemptCount(0)
                .setNextAttemptTime(LocalDateTime.now()).setLastError(null);
        require(outboxMapper.updateById(row) == 1, "完工重新打开事件人工续投保存失败");
        record(operationMapper, row, "MANUAL_RETRY", before, "RETRY_WAIT", 0, requestId,
                "人工核对PLAN事件中心后沿原事件ID续投");
        return row;
    }

    public int dispatchDue(int limit) {
        List<MesPlanCompletionReopenOutboxEntity> rows = outboxMapper.selectPage(
                new Page<>(1, Math.max(1, Math.min(limit, 200))),
                new LambdaQueryWrapper<MesPlanCompletionReopenOutboxEntity>()
                        .in(MesPlanCompletionReopenOutboxEntity::getStatus, "PENDING", "RETRY_WAIT")
                        .le(MesPlanCompletionReopenOutboxEntity::getNextAttemptTime, LocalDateTime.now())
                        .orderByAsc(MesPlanCompletionReopenOutboxEntity::getNextAttemptTime)
                        .orderByAsc(MesPlanCompletionReopenOutboxEntity::getId)).getRecords();
        int delivered = 0;
        for (MesPlanCompletionReopenOutboxEntity row : rows) {
            try {
                boolean result = row.getTenantId() == null ? worker.deliverOne(row.getId())
                        : TenantBroker.applyAs(row.getTenantId(), ignored -> worker.deliverOne(row.getId()));
                if (result) delivered++;
            } catch (RuntimeException exception) {
                try {
                    if (row.getTenantId() == null) failureService.mark(row.getId(), exception);
                    else TenantBroker.applyAs(row.getTenantId(), ignored -> {
                        failureService.mark(row.getId(), exception);
                        return null;
                    });
                } catch (RuntimeException failure) {
                    log.error("MES完工重新打开事件失败账保存异常，outboxId={}", row.getId(), failure);
                }
            }
        }
        return delivered;
    }

    static void record(MesPlanCompletionReopenOperationMapper mapper,
                       MesPlanCompletionReopenOutboxEntity outbox, String action,
                       String before, String after, int attempt, String requestId, String detail) {
        MesPlanCompletionReopenOperationEntity operation = new MesPlanCompletionReopenOperationEntity()
                .setOutboxId(outbox.getId()).setAction(action).setAttemptNo(attempt)
                .setBeforeStatus(before).setAfterStatus(after).setRequestId(requestId)
                .setDetail(StrUtil.sub(detail, 0, 500)).setOperationTime(LocalDateTime.now())
                .setFactoryCode(outbox.getFactoryCode());
        operation.setTenantId(outbox.getTenantId());
        require(mapper.insert(operation) == 1, "MES完工重新打开事件履历保存失败");
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
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
}
