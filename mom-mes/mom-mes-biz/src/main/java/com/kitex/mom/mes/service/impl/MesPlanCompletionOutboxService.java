package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesPlanCompletionDTO;
import com.kitex.mom.mes.entity.MesPlanCompletionOutboxEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesPlanCompletionMapper;
import com.kitex.mom.mes.mapper.MesPlanCompletionOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;

import static com.kitex.mom.mes.service.impl.MesPlanCompletionCodec.require;

/**
 * 完工事实与正向通知同事务；后续投递不重取工艺、库存或现场凭据。
 */
@Service
@RequiredArgsConstructor
public class MesPlanCompletionOutboxService {
    private final MesPlanCompletionMapper facts;
    private final MesPlanCompletionOutboxMapper outboxes;
    private final DataSource dataSource;
    private final ObjectMapper json;

    static boolean rework(MesTaskEntity task) {
        boolean kind = "REWORK".equals(task.getResourceType()),
                request = StrUtil.isNotBlank(task.getReworkRequestId()),
                source = StrUtil.isNotBlank(task.getSourceTaskCode());
        require(!(kind || request || source) || kind && request && source, "返工来源标记不完整");
        return kind;
    }

    static Long scope(String factory) {
        Long tenant = TenantContextHolder.getTenantId();
        require(
                tenant != null
                        && tenant > 0
                        && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                        && StrUtil.isNotBlank(factory)
                        && Objects.equals(factory, FactoryContextHolder.getFactoryCode()),
                "完工通知必须处于实际租户工厂");
        return tenant;
    }

    static boolean same(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }

    static boolean positive(BigDecimal a) {
        return a != null && a.signum() > 0;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MesPlanCompletionOutboxEntity enqueue(MesTaskEntity identity, BigDecimal completed) {
        Long tenant = scope(identity == null ? null : identity.getFactoryCode());
        require(
                identity != null && identity.getId() != null && identity.getId() > 0,
                "完工通知缺少原工单身份");
        requireTransaction();
        var task = facts.task(tenant, identity.getFactoryCode(), identity.getId());
        require(
                task != null
                        && Objects.equals(identity.getCode(), task.getCode())
                        && Objects.equals(identity.getMatCode(), task.getMatCode())
                        && Objects.equals(identity.getPlanNo(), task.getPlanNo())
                        && "FINISH".equals(task.getStatus())
                        && positive(task.getNoticeNum())
                        && same(task.getCompleteNum(), completed)
                        && completed.signum() >= 0
                        && completed.compareTo(task.getNoticeNum()) <= 0
                        && task.getFinishTime() != null,
                "完工通知必须绑定本次已保存的实际完成工单和数量");
        if (rework(task)) return null;
        int version = Objects.requireNonNullElse(task.getCompletionVersion(), 0);
        require(version >= 0, "完工通知世代无效");
        facts.lockEnqueue(
                "MES_PLAN_COMPLETION:"
                        + tenant
                        + ":"
                        + task.getFactoryCode()
                        + ":"
                        + task.getId()
                        + ":"
                        + version);
        var originals = outboxes.original(tenant, task.getFactoryCode(), task.getId(), version);
        require(originals != null && originals.size() <= 1, "完工通知世代原件重复或不可读取");
        if (!originals.isEmpty()) {
            var evidence = evidence(originals.get(0).getId(), task.getFactoryCode());
            require(
                    Boolean.TRUE.equals(evidence.getSourceValid())
                            && same(evidence.getEvent().getCompletedQuantity(), completed),
                    "完工通知世代原件与实际完成事实不符");
            return originals.get(0);
        }
        var lines = sourceLines(task, true);
        Long id = IdUtil.getSnowflakeNextId();
        var event =
                new MesPlanCompletionDTO.Event()
                        .setSchemaVersion(1)
                        .setTenantId(tenant)
                        .setFactoryCode(task.getFactoryCode())
                        .setOutboxId(id)
                        .setEventId("MES-COMPLETE:" + task.getId() + ":V" + version)
                        .setTaskId(task.getId())
                        .setTaskCode(task.getCode())
                        .setProductCode(task.getMatCode())
                        .setApsBatchNo(StrUtil.trimToNull(task.getApsBatchNo()))
                        .setCompletionVersion(version)
                        .setCompletedQuantity(completed)
                        .setCompletedAt(task.getFinishTime())
                        .setSources(lines);
        String payload = MesPlanCompletionCodec.encode(json, event);
        var row =
                new MesPlanCompletionOutboxEntity()
                        .setEventId(event.getEventId())
                        .setTaskId(task.getId())
                        .setTaskCode(task.getCode())
                        .setCompletionVersion(version)
                        .setPayloadJson(payload)
                        .setPayloadFingerprint(MesPlanCompletionCodec.hash(payload))
                        .setStatus("PENDING")
                        .setAttemptCount(0)
                        .setNextAttemptTime(LocalDateTime.now())
                        .setFactoryCode(task.getFactoryCode());
        row.setId(id);
        row.setTenantId(tenant);
        require(outboxes.insert(row) == 1, "完工通知原件保存失败");
        return row;
    }

    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public MesPlanCompletionDTO.Evidence evidence(Long id, String factory) {
        Long tenant = scope(factory);
        require(id != null && id > 0, "完工通知原件主键无效");
        var row = outboxes.scoped(tenant, factory, id);
        require(row != null, "完工通知原件不存在");
        require(
                Objects.equals(
                        row.getPayloadFingerprint(),
                        MesPlanCompletionCodec.hash(row.getPayloadJson())),
                "完工通知原文摘要不符");
        var event = MesPlanCompletionCodec.decode(json, row.getPayloadJson());
        require(
                Objects.equals(event.getTenantId(), tenant)
                        && Objects.equals(event.getFactoryCode(), factory)
                        && Objects.equals(event.getOutboxId(), row.getId())
                        && Objects.equals(event.getEventId(), row.getEventId())
                        && Objects.equals(event.getTaskId(), row.getTaskId())
                        && Objects.equals(event.getTaskCode(), row.getTaskCode())
                        && Objects.equals(event.getCompletionVersion(), row.getCompletionVersion())
                        && event.getCompletedAt() != null,
                "完工通知原件身份或世代不符");
        var task = facts.task(tenant, factory, event.getTaskId());
        require(
                task != null
                        && !rework(task)
                        && Objects.equals(task.getCode(), event.getTaskCode())
                        && Objects.equals(task.getMatCode(), event.getProductCode())
                        && Objects.equals(
                        StrUtil.trimToNull(task.getApsBatchNo()), event.getApsBatchNo())
                        && Objects.requireNonNullElse(task.getCompletionVersion(), 0)
                        >= event.getCompletionVersion(),
                "完工通知当前来源缺失或身份/世代倒退");
        var current = sourceLines(task, false);
        require(
                event.getSources() != null && current.size() == event.getSources().size(),
                "完工通知来源集合变化");
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < current.size(); i++) {
            var a = current.get(i);
            var b = event.getSources().get(i);
            require(
                    b != null
                            && Objects.equals(a.getIssueId(), b.getIssueId())
                            && Objects.equals(a.getIssueNo(), b.getIssueNo())
                            && same(a.getAllocatedQuantity(), b.getAllocatedQuantity())
                            && b.getCompletedQuantity() != null
                            && b.getCompletedQuantity().signum() >= 0
                            && b.getCompletedQuantity().compareTo(b.getAllocatedQuantity()) <= 0,
                    "完工通知来源分配与原件不一致");
            total = total.add(b.getCompletedQuantity());
        }
        require(same(total, event.getCompletedQuantity()), "完工通知逐来源实际量不守恒");
        if (Objects.requireNonNullElse(task.getCompletionVersion(), 0)
                .equals(event.getCompletionVersion())
                && "FINISH".equals(task.getStatus()))
            require(same(task.getCompleteNum(), event.getCompletedQuantity()), "同完成世代实际量发生变化");
        return new MesPlanCompletionDTO.Evidence()
                .setEvent(event)
                .setPayloadFingerprint(row.getPayloadFingerprint())
                .setSourceValid(true);
    }

    /**
     * 同一真实工单的完整分配；正向完成及批准冲销均按同一有界算法冻结本域来源量。
     */
    List<MesPlanCompletionDTO.Line> sourceLines(MesTaskEntity task, boolean freezeCompleted) {
        require(
                task != null
                        && positive(task.getNoticeNum())
                        && StrUtil.isNotBlank(task.getPlanNo()),
                "完工来源缺少计划或正数工单量");
        boolean batch = StrUtil.isNotBlank(task.getApsBatchNo());
        var rows = facts.allocations(task.getTenantId(), task.getFactoryCode(), task.getId());
        require(rows != null, "完工来源分配不可读取");
        if (!batch) {
            require(
                    task.getApsTaskId() == null
                            && task.getApsBatchId() == null
                            && StrUtil.isBlank(task.getApsBatchFingerprint())
                            && rows.isEmpty(),
                    "普通完工来源夹带不完整APS身份或分配");
            return List.of(
                    new MesPlanCompletionDTO.Line()
                            .setIssueNo(task.getPlanNo())
                            .setAllocatedQuantity(task.getNoticeNum())
                            .setCompletedQuantity(
                                    Objects.requireNonNullElse(
                                            task.getCompleteNum(), BigDecimal.ZERO)));
        }
        require(
                task.getApsTaskId() != null
                        && task.getApsBatchId() != null
                        && task.getApsBatchFingerprint() != null
                        && task.getApsBatchFingerprint().matches("[0-9a-f]{64}")
                        && !rows.isEmpty()
                        && rows.size() <= 100,
                "APS完工来源身份或分配缺失");
        Set<Long> ids = new HashSet<>();
        Set<Integer> seq = new HashSet<>();
        Set<Long> issues = new HashSet<>();
        Set<String> numbers = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (var row : rows) {
            require(
                    row != null
                            && row.getId() != null
                            && ids.add(row.getId())
                            && row.getAllocationSeq() != null
                            && row.getAllocationSeq() > 0
                            && seq.add(row.getAllocationSeq())
                            && Objects.equals(row.getTenantId(), task.getTenantId())
                            && Objects.equals(row.getFactoryCode(), task.getFactoryCode())
                            && Objects.equals(row.getMesTaskId(), task.getId())
                            && Objects.equals(row.getMesTaskCode(), task.getCode())
                            && Objects.equals(row.getApsTaskId(), task.getApsTaskId())
                            && Objects.equals(row.getApsBatchId(), task.getApsBatchId())
                            && Objects.equals(row.getApsBatchNo(), task.getApsBatchNo())
                            && Objects.equals(
                            row.getApsBatchFingerprint(), task.getApsBatchFingerprint())
                            && row.getIssueId() != null
                            && row.getIssueId() > 0
                            && issues.add(row.getIssueId())
                            && StrUtil.isNotBlank(row.getIssueNo())
                            && numbers.add(row.getIssueNo())
                            && positive(row.getAllocatedQuantity())
                            && positive(row.getIssueQuantitySnapshot())
                            && row.getAllocatedQuantity().compareTo(row.getIssueQuantitySnapshot())
                            <= 0,
                    "APS完工完整分配身份、唯一性或数量不符");
            total = total.add(row.getAllocatedQuantity());
        }
        require(same(total, task.getNoticeNum()), "APS完工分配与工单量不守恒");
        BigDecimal actual = Objects.requireNonNullElse(task.getCompleteNum(), BigDecimal.ZERO);
        require(actual.signum() >= 0 && actual.compareTo(total) <= 0, "APS实际完成量超出原工单");
        BigDecimal cumulativeAllocation = BigDecimal.ZERO;
        BigDecimal assigned = BigDecimal.ZERO;
        var result = new ArrayList<MesPlanCompletionDTO.Line>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            BigDecimal quantity = row.getCompletedQuantity();
            if (freezeCompleted) {
                // 对累计来源量舍入，再取相邻累计值之差，避免多行尾差挤入极小的末行。
                cumulativeAllocation = cumulativeAllocation.add(row.getAllocatedQuantity());
                BigDecimal target =
                        i == rows.size() - 1
                                ? actual
                                : actual.multiply(cumulativeAllocation)
                                .divide(total, 8, RoundingMode.HALF_UP);
                quantity = target.subtract(assigned);
                require(
                        quantity.signum() >= 0
                                && quantity.compareTo(row.getAllocatedQuantity()) <= 0,
                        "APS完工来源舍入量超出原分配范围");
                assigned = target;
                require(
                        facts.completed(
                                task.getTenantId(),
                                task.getFactoryCode(),
                                task.getId(),
                                row.getId(),
                                quantity)
                                == 1,
                        "APS完工来源量保存失败");
            }
            result.add(
                    new MesPlanCompletionDTO.Line()
                            .setIssueId(row.getIssueId())
                            .setIssueNo(row.getIssueNo())
                            .setAllocatedQuantity(row.getAllocatedQuantity())
                            .setCompletedQuantity(quantity));
        }
        return List.copyOf(result);
    }

    private void requireTransaction() {
        require(TransactionSynchronizationManager.isActualTransactionActive(), "完工通知必须处于原业务事务");
        var connection = DataSourceUtils.getConnection(dataSource);
        try {
            require(
                    DataSourceUtils.isConnectionTransactional(connection, dataSource)
                            && connection.getTransactionIsolation()
                            == Connection.TRANSACTION_READ_COMMITTED,
                    "完工通知需要同数据源RC事务");
        } catch (SQLException e) {
            throw new CheckedException("无法核验完工通知事务");
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
