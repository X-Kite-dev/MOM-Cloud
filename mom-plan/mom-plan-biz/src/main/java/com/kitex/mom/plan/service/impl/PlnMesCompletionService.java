package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.api.feign.RemoteMesPlanCompletionService;
import com.kitex.mom.plan.dto.PlanMesCompletionDTO;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnMesCompletionReceiptEntity;
import com.kitex.mom.plan.mapper.PlnMesCompletionReceiptMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 先核不可变原件，在来源计划锁内读MES当前完整事实，再持久化唯一决策回执。
 */
@Service
@RequiredArgsConstructor
public class PlnMesCompletionService {
    private final PlnMesCompletionReceiptMapper receipts;
    private final RemoteMesPlanCompletionService remote;
    private final ObjectMapper json;
    private final DataSource dataSource;

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }

    private static boolean positive(BigDecimal a) {
        return a != null && a.signum() > 0;
    }

    private static boolean hash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new CheckedException(message);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public PlanMesCompletionDTO.Receipt reconcile(PlanMesCompletionDTO.Command command) {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        require(
                tenant != null
                        && tenant > 0
                        && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                        && StrUtil.isNotBlank(factory)
                        && command != null
                        && Objects.equals(factory, command.getFactoryCode())
                        && command.getOutboxId() != null
                        && command.getOutboxId() > 0
                        && StrUtil.isNotBlank(command.getEventId())
                        && command.getEventId().length() <= 200
                        && hash(command.getPayloadFingerprint()),
                "完工通知缺少实际组织或原事件身份");
        requireTransaction();
        receipts.lockRequest(
                "PLN_MES_COMPLETION:" + tenant + ":" + factory + ":" + command.getOutboxId());
        var original = receipts.originals(tenant, factory, command.getOutboxId());
        require(original != null, "完工通知处理原件不可读取");
        if (!original.isEmpty()) return replay(command, tenant, original);
        var response = remote.evidence(command.getOutboxId(), factory);
        require(response != null && response.isOk() && response.getData() != null, "MES完工原件暂不可核验");
        var proof = response.getData();
        var event = proof.getEvent();
        require(
                Boolean.TRUE.equals(proof.getSourceValid())
                        && Objects.equals(
                        proof.getPayloadFingerprint(), command.getPayloadFingerprint())
                        && event != null
                        && Integer.valueOf(1).equals(event.getSchemaVersion())
                        && Objects.equals(event.getTenantId(), tenant)
                        && Objects.equals(event.getFactoryCode(), factory)
                        && Objects.equals(event.getOutboxId(), command.getOutboxId())
                        && Objects.equals(event.getEventId(), command.getEventId())
                        && event.getTaskId() != null
                        && event.getTaskId() > 0
                        && StrUtil.isNotBlank(event.getTaskCode())
                        && StrUtil.isNotBlank(event.getProductCode())
                        && event.getCompletionVersion() != null
                        && event.getCompletionVersion() >= 0
                        && Objects.equals(
                        event.getEventId(),
                        "MES-COMPLETE:"
                                + event.getTaskId()
                                + ":V"
                                + event.getCompletionVersion())
                        && event.getCompletedAt() != null
                        && event.getCompletedQuantity() != null
                        && event.getCompletedQuantity().signum() >= 0,
                "MES完工原件来源、身份、世代或摘要不符");
        require(
                event.getSources() != null
                        && !event.getSources().isEmpty()
                        && event.getSources().size() <= 100,
                "MES完工原件来源范围无效");
        var plans = new TreeMap<Long, PlnIssueEntity>();
        Set<String> numbers = new HashSet<>();
        BigDecimal completed = BigDecimal.ZERO;
        for (var line : event.getSources()) {
            require(
                    line != null
                            && StrUtil.isNotBlank(line.getIssueNo())
                            && numbers.add(line.getIssueNo())
                            && positive(line.getAllocatedQuantity())
                            && line.getCompletedQuantity() != null
                            && line.getCompletedQuantity().signum() >= 0
                            && line.getCompletedQuantity().compareTo(line.getAllocatedQuantity())
                            <= 0
                            && (StrUtil.isBlank(event.getApsBatchNo())
                            ? event.getSources().size() == 1 && line.getIssueId() == null
                            : line.getIssueId() != null && line.getIssueId() > 0),
                    "MES完工逐计划来源重复、缺失或数量无效");
            completed = completed.add(line.getCompletedQuantity());
            var found =
                    receipts.source(
                            tenant,
                            factory,
                            line.getIssueId(),
                            line.getIssueNo(),
                            event.getProductCode());
            require(
                    found != null
                            && found.size() == 1
                            && found.get(0).getId() != null
                            && !plans.containsKey(found.get(0).getId()),
                    "MES完工来源未唯一对应实际计划");
            plans.put(found.get(0).getId(), found.get(0));
        }
        require(same(completed, event.getCompletedQuantity()), "MES完工原件逐来源实际量不守恒");
        // 不同批次可以覆盖相同多计划，必须统一ID升序锁，且进度查询在取锁后执行。
        for (var entry : plans.entrySet()) {
            var locked = receipts.lockedIssue(tenant, factory, entry.getKey());
            var before = entry.getValue();
            require(
                    locked != null
                            && Objects.equals(locked.getIssueNo(), before.getIssueNo())
                            && Objects.equals(locked.getProductCode(), event.getProductCode())
                            && "0".equals(locked.getDelFlag())
                            && positive(locked.getNum())
                            && List.of("1", "2", "3", "4").contains(locked.getStatus()),
                    "MES完工来源计划已删除、未下发或状态不合法");
            entry.setValue(locked);
        }
        var recorded = new ArrayList<PlnMesCompletionReceiptEntity>();
        for (var issue : plans.values()) {
            var row =
                    new PlnMesCompletionReceiptEntity()
                            .setOutboxId(command.getOutboxId())
                            .setEventId(command.getEventId())
                            .setPayloadFingerprint(command.getPayloadFingerprint())
                            .setMesTaskId(event.getTaskId())
                            .setMesTaskCode(event.getTaskCode())
                            .setCompletionVersion(event.getCompletionVersion())
                            .setSourceCount(plans.size())
                            .setIssueId(issue.getId())
                            .setIssueNo(issue.getIssueNo())
                            .setStatusBefore(issue.getStatus())
                            .setFactoryCode(factory)
                            .setRecordedTime(LocalDateTime.now());
            row.setTenantId(tenant);
            if ("4".equals(issue.getStatus()))
                row.setDecision("CLOSED_IGNORED").setStatusAfter("4");
            else {
                var current =
                        remote.progress(
                                command.getOutboxId(), issue.getId(), issue.getIssueNo(), factory);
                require(
                        current != null && current.isOk() && current.getData() != null,
                        "MES完整来源进度暂不可核验");
                var progress = current.getData();
                require(
                        Objects.equals(progress.getTenantId(), tenant)
                                && Objects.equals(progress.getFactoryCode(), factory)
                                && Objects.equals(progress.getOutboxId(), command.getOutboxId())
                                && Objects.equals(
                                progress.getPayloadFingerprint(),
                                command.getPayloadFingerprint())
                                && Objects.equals(progress.getIssueId(), issue.getId())
                                && Objects.equals(progress.getIssueNo(), issue.getIssueNo())
                                && Objects.equals(progress.getProductCode(), issue.getProductCode())
                                && progress.getTaskCount() != null
                                && progress.getTaskCount() >= 0
                                && progress.getAllFinished() != null
                                && hash(progress.getSourceFingerprint())
                                && progress.getAllocatedQuantity() != null
                                && progress.getAllocatedQuantity().signum() >= 0
                                && progress.getAllocatedQuantity().compareTo(issue.getNum()) <= 0
                                && progress.getCompletedQuantity() != null
                                && progress.getCompletedQuantity().signum() >= 0
                                && progress.getCompletedQuantity()
                                .compareTo(progress.getAllocatedQuantity())
                                <= 0
                                && (!Boolean.TRUE.equals(progress.getAllFinished())
                                || progress.getTaskCount() > 0
                                && progress.getCompletedAt() != null),
                        "MES完整来源范围、数量或完成依据不符");
                boolean finish =
                        same(progress.getAllocatedQuantity(), issue.getNum())
                                && Boolean.TRUE.equals(progress.getAllFinished());
                String after = finish ? "3" : "2";
                require(
                        receipts.decision(
                                tenant,
                                factory,
                                issue.getId(),
                                issue.getStatus(),
                                after,
                                finish ? progress.getCompletedAt().toLocalDate() : null)
                                == 1,
                        "生产计划完成汇总保存失败");
                row.setAllocatedQuantity(progress.getAllocatedQuantity())
                        .setProductionQuantity(progress.getCompletedQuantity())
                        .setSourceFingerprint(progress.getSourceFingerprint())
                        .setDecision(finish ? "COMPLETED" : "PARTIAL")
                        .setStatusAfter(after);
            }
            require(receipts.insert(row) == 1, "生产计划完工处理回执保存失败");
            recorded.add(row);
        }
        return replay(command, tenant, recorded);
    }

    private PlanMesCompletionDTO.Receipt replay(
            PlanMesCompletionDTO.Command command,
            Long tenant,
            List<PlnMesCompletionReceiptEntity> rows) {
        Set<Long> ids = new HashSet<>(), issues = new HashSet<>();
        for (var row : rows)
            require(
                    row != null
                            && row.getId() != null
                            && row.getId() > 0
                            && ids.add(row.getId())
                            && row.getIssueId() != null
                            && issues.add(row.getIssueId())
                            && Objects.equals(row.getTenantId(), tenant)
                            && Objects.equals(row.getFactoryCode(), command.getFactoryCode())
                            && Objects.equals(row.getOutboxId(), command.getOutboxId())
                            && Objects.equals(row.getEventId(), command.getEventId())
                            && Objects.equals(
                            row.getPayloadFingerprint(), command.getPayloadFingerprint())
                            && Objects.equals(row.getSourceCount(), rows.size())
                            && row.getMesTaskId() != null
                            && row.getCompletionVersion() != null
                            && row.getCompletionVersion() >= 0
                            && StrUtil.isNotBlank(row.getMesTaskCode())
                            && StrUtil.isNotBlank(row.getIssueNo())
                            && row.getRecordedTime() != null
                            && List.of("PARTIAL", "COMPLETED", "CLOSED_IGNORED")
                            .contains(row.getDecision()),
                    "完工通知已存在不同载荷或不完整处理回执");
        return new PlanMesCompletionDTO.Receipt()
                .setOutboxId(command.getOutboxId())
                .setEventId(command.getEventId())
                .setPayloadFingerprint(command.getPayloadFingerprint())
                .setReceiptIds(rows.stream().map(PlnMesCompletionReceiptEntity::getId).toList());
    }

    private void requireTransaction() {
        require(TransactionSynchronizationManager.isActualTransactionActive(), "完工通知处理必须处于事务");
        var c = DataSourceUtils.getConnection(dataSource);
        try {
            require(
                    DataSourceUtils.isConnectionTransactional(c, dataSource)
                            && c.getTransactionIsolation() == Connection.TRANSACTION_READ_COMMITTED,
                    "完工通知处理需要同数据源RC事务");
        } catch (SQLException e) {
            throw new CheckedException("无法核验计划完工事务");
        } finally {
            DataSourceUtils.releaseConnection(c, dataSource);
        }
    }
}
