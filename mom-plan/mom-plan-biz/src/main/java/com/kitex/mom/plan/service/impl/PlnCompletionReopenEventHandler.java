package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.api.feign.RemoteMesPlanCompletionReopenService;
import com.kitex.mom.mes.dto.MesPlanCompletionReopenEvidence;
import com.kitex.mom.plan.entity.PlnAutomationEventEntity;
import com.kitex.mom.plan.entity.PlnCompletionReopenReceiptEntity;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.mapper.PlnCompletionReopenReceiptMapper;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * PLAN核验MES不可变来源后，重新打开已完成的来源计划。
 */
@Service
@RequiredArgsConstructor
public class PlnCompletionReopenEventHandler {

    private final RemoteMesPlanCompletionReopenService remoteEvidenceService;
    private final PlnCompletionReopenReceiptMapper receiptMapper;
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;
    private final PlnMesCompletionReceiptMapper completionReceipts;

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ApplyResult apply(PlnAutomationEventEntity event, JsonNode payloadNode) {
        require(
                event != null
                        && event.getTenantId() != null
                        && event.getTenantId() > 0
                        && Objects.equals(TenantContextHolder.getTenantId(), event.getTenantId())
                        && !TenantContextHolder.getTenantSkip()
                        && StrUtil.isNotBlank(event.getFactoryCode())
                        && Objects.equals(
                        FactoryContextHolder.getFactoryCode(), event.getFactoryCode())
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()),
                "重新打开计划须实际租户工厂");
        requireTransaction();
        require(
                "MES".equals(event.getSourceSystem())
                        && "MES_COMPLETION_REOPENED".equals(event.getEventType())
                        && "MES_TASK".equals(event.getAggregateType()),
                "完工重新打开事件来源、类型或聚合对象不一致");
        MesPlanCompletionReopenEvidence payload = readPayload(event);
        int schema =
                payload.getSourceSchemaVersion() == null ? 1 : payload.getSourceSchemaVersion();
        require(
                (schema == 1 || schema == 2)
                        && Objects.equals(
                        Objects.requireNonNullElse(event.getEventVersion(), 1), schema),
                "反向完成载荷与事件版本不一致或未知");
        require(
                payload.getOutboxId() != null
                        && payload.getTaskId() != null
                        && payload.getReverseRequestId() != null
                        && payload.getCompletionVersionAfter() != null,
                "完工重新打开事件缺少来源主键或版本");
        require(
                payload.getCompletionVersionBefore() != null
                        && payload.getCompletionVersionBefore() >= 0
                        && (long) payload.getCompletionVersionAfter()
                        == (long) payload.getCompletionVersionBefore() + 1
                        && payload.getCompleteQuantityBefore() != null
                        && payload.getCompleteQuantityAfter() != null,
                "完工重新打开事件的完成世代或冲销前后数量无效");
        require(
                Objects.equals(event.getEventId(), payload.getEventId())
                        && Objects.equals(
                        event.getAggregateId(), String.valueOf(payload.getTaskId()))
                        && Objects.equals(event.getFactoryCode(), payload.getFactoryCode()),
                "完工重新打开事件信封与MES来源载荷不一致");
        var response =
                remoteEvidenceService.evidence(payload.getOutboxId(), event.getFactoryCode());
        if (response == null || !response.isOk() || response.getData() == null) {
            throw new IllegalStateException("MES完工重新打开来源暂不可核验");
        }
        MesPlanCompletionReopenEvidence proof = response.getData();
        require(
                Boolean.TRUE.equals(proof.getSourceValid())
                        && Objects.equals(
                        proof.getSourceSchemaVersion(), payload.getSourceSchemaVersion())
                        && Objects.equals(proof.getTenantId(), event.getTenantId())
                        && Objects.equals(proof.getFactoryCode(), event.getFactoryCode())
                        && Objects.equals(proof.getOutboxId(), payload.getOutboxId())
                        && Objects.equals(proof.getEventId(), event.getEventId())
                        && Objects.equals(
                        proof.getReverseRequestId(), payload.getReverseRequestId())
                        && Objects.equals(proof.getTaskId(), payload.getTaskId())
                        && Objects.equals(proof.getTaskCode(), payload.getTaskCode())
                        && Objects.equals(proof.getPlanNo(), payload.getPlanNo())
                        && Objects.equals(proof.getApsBatchNo(), payload.getApsBatchNo())
                        && Objects.equals(proof.getProductCode(), payload.getProductCode())
                        && Objects.equals(
                        proof.getCompletionVersionBefore(),
                        payload.getCompletionVersionBefore())
                        && Objects.equals(
                        proof.getCompletionVersionAfter(),
                        payload.getCompletionVersionAfter())
                        && matchesQuantity(
                        proof.getCompleteQuantityBefore(),
                        payload.getCompleteQuantityBefore(),
                        schema)
                        && matchesQuantity(
                        proof.getCompleteQuantityAfter(),
                        payload.getCompleteQuantityAfter(),
                        schema)
                        && matchesLines(proof.getSourceLines(), payload.getSourceLines(), schema)
                        && (schema == 1
                        || Objects.equals(
                        proof.getPayloadFingerprint(),
                        event.getPayloadFingerprint()))
                        && StrUtil.isNotBlank(proof.getPayloadFingerprint()),
                "MES完工重新打开来源、版本或逐计划完成量与PLAN收件载荷不一致");
        require(
                proof.getCompleteQuantityBefore().compareTo(proof.getCompleteQuantityAfter()) > 0
                        && proof.getCompleteQuantityAfter().signum() >= 0,
                "MES原件的冲销前后数量无效");
        require(
                proof.getSourceLines() != null
                        && !proof.getSourceLines().isEmpty()
                        && proof.getSourceLines().size() <= 100,
                "完工重新打开事件的来源计划范围为空或超过100行");

        String completionSource =
                StrUtil.blankToDefault(proof.getApsBatchNo(), proof.getTaskCode());
        List<PlnCompletionReopenReceiptEntity> receipts = new ArrayList<>();
        var sourceNumbers = new HashSet<String>();
        var sourceIds = new HashSet<Long>();
        BigDecimal sourceCompleted = BigDecimal.ZERO, sourceAllocated = BigDecimal.ZERO;
        for (var line : proof.getSourceLines()) {
            validateLine(line, schema);
            require(
                    sourceNumbers.add(line.getIssueNo())
                            && (line.getIssueId() == null
                            || line.getIssueId() > 0 && sourceIds.add(line.getIssueId())),
                    "反向完成来源计划重复或主键无效");
            sourceAllocated = sourceAllocated.add(line.getAllocatedQuantity());
            sourceCompleted = sourceCompleted.add(line.getCompletedQuantity());
        }
        if (schema == 2)
            require(
                    sourceCompleted.compareTo(proof.getCompleteQuantityAfter()) == 0
                            && proof.getCompleteQuantityBefore().compareTo(sourceAllocated) <= 0
                            && proof.getSourceLines().stream()
                            .allMatch(
                                    line ->
                                            StrUtil.isBlank(proof.getApsBatchNo())
                                                    ? proof.getSourceLines().size() == 1
                                                    && line.getIssueId() == null
                                                    : line.getIssueId() != null),
                    "新反向完成来源集合或实际量不守恒");
        // 与正向完成处理统一按计划ID取锁，避免同一批次不同来源顺序交叉锁定。
        var sourceLines =
                proof.getSourceLines().stream()
                        .sorted(
                                Comparator.comparing(
                                        MesPlanCompletionReopenEvidence.SourceLine::getIssueId,
                                        Comparator.nullsFirst(Long::compareTo)))
                        .toList();
        for (MesPlanCompletionReopenEvidence.SourceLine line : sourceLines) {
            validateLine(line, schema);
            PlnIssueEntity issue = lockIssue(event, proof, line);
            var originals =
                    receiptMapper.original(
                            event.getTenantId(),
                            event.getFactoryCode(),
                            proof.getOutboxId(),
                            issue.getId());
            require(originals != null && originals.size() <= 1, "反向完成原回执重复或不可读取");
            if (!originals.isEmpty()) {
                var original = originals.get(0);
                require(
                        Objects.equals(original.getInboxId(), event.getId())
                                && Objects.equals(
                                original.getReverseRequestId(), proof.getReverseRequestId())
                                && Objects.equals(original.getMesTaskId(), proof.getTaskId())
                                && Objects.equals(original.getMesTaskCode(), proof.getTaskCode())
                                && Objects.equals(
                                original.getCompletionVersion(),
                                proof.getCompletionVersionAfter())
                                && Objects.equals(
                                original.getSourceFingerprint(),
                                proof.getPayloadFingerprint())
                                && Objects.equals(
                                original.getSourceSchemaVersion(),
                                proof.getSourceSchemaVersion()),
                        "反向完成原回执与事件身份或模型不一致");
                receipts.add(original);
                continue;
            }
            Object completionReference =
                    StrUtil.isBlank(proof.getApsBatchNo()) ? issue.getIssueNo() : issue.getId();
            String completionRequestId =
                    "MES:"
                            + completionSource
                            + ":COMPLETE_V"
                            + proof.getCompletionVersionAfter()
                            + ':'
                            + completionReference;
            var legacy =
                    receiptMapper.legacyCompletion(
                            event.getTenantId(), event.getFactoryCode(), completionRequestId);
            require(legacy != null && legacy.size() <= 1, "历史完成回执重复或不可读取");
            if (!legacy.isEmpty())
                require(
                        Objects.equals(legacy.get(0).getIssueId(), issue.getId())
                                && Objects.equals(legacy.get(0).getIssueNo(), issue.getIssueNo()),
                        "历史完成原号与来源计划不一致");
            var positive =
                    completionReceipts.generation(
                            event.getTenantId(),
                            event.getFactoryCode(),
                            proof.getTaskId(),
                            proof.getTaskCode(),
                            proof.getCompletionVersionAfter(),
                            issue.getId());
            require(positive != null, "PLAN正向完工处理凭据不可读取");
            for (var completed : positive) {
                require(
                        Objects.equals(completed.getIssueNo(), issue.getIssueNo())
                                && StrUtil.isNotBlank(completed.getPayloadFingerprint())
                                && List.of("PARTIAL", "COMPLETED", "CLOSED_IGNORED")
                                .contains(completed.getDecision()),
                        "PLAN正向完工处理凭据不完整");
            }
            String beforeStatus = issue.getStatus();
            LocalDate finishDateBefore = issue.getFinishTime();
            require(
                    List.of("2", "3", "4").contains(beforeStatus),
                    "PLAN来源计划已离开可重新打开状态：" + issue.getIssueNo() + "/" + beforeStatus);
            String result;
            if ("4".equals(beforeStatus)) result = "CLOSED_IGNORED";
            else if (legacy.isEmpty() && positive.isEmpty()) {
                require(
                        receiptMapper.reopen(
                                event.getTenantId(),
                                event.getFactoryCode(),
                                issue.getId(),
                                beforeStatus)
                                == 1,
                        "PLAN来源计划重新打开失败：" + issue.getIssueNo());
                result = "APPLIED";
            } else result = "SUPERSEDED";
            PlnCompletionReopenReceiptEntity receipt =
                    new PlnCompletionReopenReceiptEntity()
                            .setSourceSchemaVersion(proof.getSourceSchemaVersion())
                            .setInboxId(event.getId())
                            .setMesOutboxId(proof.getOutboxId())
                            .setReverseRequestId(proof.getReverseRequestId())
                            .setMesTaskId(proof.getTaskId())
                            .setMesTaskCode(proof.getTaskCode())
                            .setCompletionVersion(proof.getCompletionVersionAfter())
                            .setIssueId(issue.getId())
                            .setIssueNo(issue.getIssueNo())
                            .setStatusBefore(beforeStatus)
                            .setStatusAfter("APPLIED".equals(result) ? "2" : issue.getStatus())
                            .setFinishDateBefore(finishDateBefore)
                            .setAllocatedQuantity(line.getAllocatedQuantity())
                            .setCompletedQuantity(line.getCompletedQuantity())
                            .setApplyResult(result)
                            .setSourceFingerprint(proof.getPayloadFingerprint())
                            .setAppliedTime(LocalDateTime.now())
                            .setFactoryCode(event.getFactoryCode());
            receipt.setTenantId(event.getTenantId());
            require(receiptMapper.insert(receipt) == 1, "PLAN完工重新打开回执保存失败");
            receipts.add(receipt);
        }
        long applied =
                receipts.stream().filter(row -> "APPLIED".equals(row.getApplyResult())).count();
        long superseded =
                receipts.stream().filter(row -> "SUPERSEDED".equals(row.getApplyResult())).count();
        long closed =
                receipts.stream()
                        .filter(row -> "CLOSED_IGNORED".equals(row.getApplyResult()))
                        .count();
        return new ApplyResult(
                List.copyOf(receipts),
                applied,
                "已核验MES批准冲销来源：重新打开"
                        + applied
                        + "张计划，"
                        + superseded
                        + "张已有同或更新完成世代，"
                        + closed
                        + "张保留关闭");
    }

    private MesPlanCompletionReopenEvidence readPayload(PlnAutomationEventEntity event) {
        String raw = event.getPayloadJson();
        require(raw != null && raw.length() <= 1_000_000, "反向完成收件原文缺失或范围无效");
        try {
            if (Objects.requireNonNullElse(event.getEventVersion(), 1) == 1)
                return cn.hutool.json.JSONUtil.toBean(raw, MesPlanCompletionReopenEvidence.class);
            return objectMapper
                    .copy()
                    .enable(
                            com.fasterxml.jackson.core.JsonParser.Feature
                                    .STRICT_DUPLICATE_DETECTION)
                    .enable(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(raw, MesPlanCompletionReopenEvidence.class);
        } catch (Exception failure) {
            throw new CheckedException("反向完成收件原文不可读取");
        }
    }

    private boolean matchesQuantity(BigDecimal original, BigDecimal received, int schema) {
        if (original == null || received == null) return false;
        if (schema == 2) return original.compareTo(received) == 0;
        // 历史收件先经过JsonNode规范化，校验沿用原传输表示；执行始终使用MES原件精确值。
        try {
            BigDecimal transported = objectMapper.readTree(original.toPlainString()).decimalValue();
            return transported.compareTo(received) == 0;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new CheckedException("历史反向完成数量不可读取");
        }
    }

    private boolean matchesLines(
            List<MesPlanCompletionReopenEvidence.SourceLine> original,
            List<MesPlanCompletionReopenEvidence.SourceLine> received,
            int schema) {
        if (original == null || received == null || original.size() != received.size())
            return false;
        for (int i = 0; i < original.size(); i++) {
            var a = original.get(i);
            var b = received.get(i);
            if (a == null
                    || b == null
                    || !Objects.equals(a.getIssueId(), b.getIssueId())
                    || !Objects.equals(a.getIssueNo(), b.getIssueNo())
                    || !matchesQuantity(a.getAllocatedQuantity(), b.getAllocatedQuantity(), schema)
                    || !matchesQuantity(a.getCompletedQuantity(), b.getCompletedQuantity(), schema))
                return false;
        }
        return true;
    }

    private PlnIssueEntity lockIssue(
            PlnAutomationEventEntity event,
            MesPlanCompletionReopenEvidence proof,
            MesPlanCompletionReopenEvidence.SourceLine line) {
        var found =
                completionReceipts.source(
                        event.getTenantId(),
                        event.getFactoryCode(),
                        line.getIssueId(),
                        line.getIssueNo(),
                        proof.getProductCode());
        require(found != null && found.size() == 1, "反向完成未唯一对应实际来源计划：" + line.getIssueNo());
        var issue =
                completionReceipts.lockedIssue(
                        event.getTenantId(), event.getFactoryCode(), found.get(0).getId());
        require(
                issue != null
                        && Objects.equals(issue.getTenantId(), event.getTenantId())
                        && Objects.equals(issue.getIssueNo(), line.getIssueNo())
                        && "0".equals(issue.getDelFlag())
                        && Objects.equals(issue.getProductCode(), proof.getProductCode())
                        && issue.getNum() != null
                        && issue.getNum().compareTo(line.getAllocatedQuantity()) >= 0,
                "PLAN来源计划不存在，或租户、工厂、产品、来源分配量不一致：" + line.getIssueNo());
        return issue;
    }

    private void validateLine(MesPlanCompletionReopenEvidence.SourceLine line, int schema) {
        BigDecimal allocated = line == null ? null : line.getAllocatedQuantity();
        BigDecimal completed = line == null ? null : line.getCompletedQuantity();
        require(
                line != null
                        && StrUtil.isNotBlank(line.getIssueNo())
                        && allocated != null
                        && completed != null
                        && allocated.signum() > 0
                        && completed.signum() >= 0
                        && (schema == 2
                        ? completed.compareTo(allocated) <= 0
                        : completed.compareTo(allocated) < 0),
                "MES完工重新打开逐计划数量无效");
    }

    private void requireTransaction() {
        require(TransactionSynchronizationManager.isActualTransactionActive(), "重新打开计划须处于事件处理事务");
        var c = DataSourceUtils.getConnection(dataSource);
        try {
            require(
                    DataSourceUtils.isConnectionTransactional(c, dataSource)
                            && c.getTransactionIsolation() == Connection.TRANSACTION_READ_COMMITTED,
                    "重新打开计划需要同数据源RC事务");
        } catch (SQLException failure) {
            throw new CheckedException("无法核验重新打开计划事务");
        } finally {
            DataSourceUtils.releaseConnection(c, dataSource);
        }
    }

    public record ApplyResult(
            List<PlnCompletionReopenReceiptEntity> receipts, long appliedCount, String summary) {
    }
}
