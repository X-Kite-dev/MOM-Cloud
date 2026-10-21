package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.api.feign.RemoteMesPlanCompletionReopenService;
import com.kitex.mom.mes.dto.MesPlanCompletionReopenEvidence;
import com.kitex.mom.plan.entity.PlnAutomationEventEntity;
import com.kitex.mom.plan.entity.PlnCompletionReopenReceiptEntity;
import com.kitex.mom.plan.entity.PlnIssueCallbackReceiptEntity;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.mapper.PlnCompletionReopenReceiptMapper;
import com.kitex.mom.plan.mapper.PlnIssueCallbackReceiptMapper;
import com.kitex.mom.plan.mapper.PlnIssueMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * PLAN核验MES不可变来源后，重新打开已完成的来源计划。
 */
@Service
@RequiredArgsConstructor
public class PlnCompletionReopenEventHandler {

    private final RemoteMesPlanCompletionReopenService remoteEvidenceService;
    private final PlnIssueMapper issueMapper;
    private final PlnIssueCallbackReceiptMapper callbackReceiptMapper;
    private final PlnCompletionReopenReceiptMapper receiptMapper;
    private final ObjectMapper objectMapper;

    public ApplyResult apply(PlnAutomationEventEntity event, JsonNode payloadNode) {
        require("MES".equals(event.getSourceSystem())
                        && "MES_COMPLETION_REOPENED".equals(event.getEventType())
                        && "MES_TASK".equals(event.getAggregateType()),
                "完工重新打开事件来源、类型或聚合对象不一致");
        MesPlanCompletionReopenEvidence payload = objectMapper.convertValue(payloadNode,
                MesPlanCompletionReopenEvidence.class);
        require(payload.getOutboxId() != null && payload.getTaskId() != null
                        && payload.getReverseRequestId() != null && payload.getCompletionVersionAfter() != null,
                "完工重新打开事件缺少来源主键或版本");
        require(payload.getCompletionVersionBefore() != null
                        && payload.getCompletionVersionAfter() == payload.getCompletionVersionBefore() + 1
                        && payload.getCompleteQuantityBefore() != null
                        && payload.getCompleteQuantityAfter() != null
                        && payload.getCompleteQuantityBefore().compareTo(payload.getCompleteQuantityAfter()) > 0
                        && payload.getCompleteQuantityAfter().signum() >= 0,
                "完工重新打开事件的完成世代或冲销前后数量无效");
        require(Objects.equals(event.getEventId(), payload.getEventId())
                        && Objects.equals(event.getAggregateId(), String.valueOf(payload.getTaskId()))
                        && Objects.equals(event.getFactoryCode(), payload.getFactoryCode()),
                "完工重新打开事件信封与MES来源载荷不一致");
        var response = remoteEvidenceService.evidence(payload.getOutboxId(), event.getFactoryCode());
        if (response == null || !response.isOk() || response.getData() == null) {
            throw new IllegalStateException("MES完工重新打开来源暂不可核验");
        }
        MesPlanCompletionReopenEvidence proof = response.getData();
        require(Boolean.TRUE.equals(proof.getSourceValid())
                        && Objects.equals(proof.getTenantId(), event.getTenantId())
                        && Objects.equals(proof.getFactoryCode(), event.getFactoryCode())
                        && Objects.equals(proof.getOutboxId(), payload.getOutboxId())
                        && Objects.equals(proof.getEventId(), event.getEventId())
                        && Objects.equals(proof.getReverseRequestId(), payload.getReverseRequestId())
                        && Objects.equals(proof.getTaskId(), payload.getTaskId())
                        && Objects.equals(proof.getTaskCode(), payload.getTaskCode())
                        && Objects.equals(proof.getPlanNo(), payload.getPlanNo())
                        && Objects.equals(proof.getApsBatchNo(), payload.getApsBatchNo())
                        && Objects.equals(proof.getProductCode(), payload.getProductCode())
                        && Objects.equals(proof.getCompletionVersionBefore(), payload.getCompletionVersionBefore())
                        && Objects.equals(proof.getCompletionVersionAfter(), payload.getCompletionVersionAfter())
                        && Objects.equals(proof.getCompleteQuantityBefore(), payload.getCompleteQuantityBefore())
                        && Objects.equals(proof.getCompleteQuantityAfter(), payload.getCompleteQuantityAfter())
                        && Objects.equals(proof.getSourceLines(), payload.getSourceLines())
                        && StrUtil.isNotBlank(proof.getPayloadFingerprint()),
                "MES完工重新打开来源、版本或逐计划完成量与PLAN收件载荷不一致");
        require(proof.getSourceLines() != null && !proof.getSourceLines().isEmpty()
                        && proof.getSourceLines().size() <= 100,
                "完工重新打开事件的来源计划范围为空或超过100行");

        String completionSource = StrUtil.blankToDefault(proof.getApsBatchNo(), proof.getTaskCode());
        List<PlnCompletionReopenReceiptEntity> receipts = new ArrayList<>();
        for (MesPlanCompletionReopenEvidence.SourceLine line : proof.getSourceLines()) {
            validateLine(line);
            PlnIssueEntity issue = lockIssue(event, proof, line);
            PlnCompletionReopenReceiptEntity existing = receiptMapper.selectOne(
                    new LambdaQueryWrapper<PlnCompletionReopenReceiptEntity>()
                            .eq(PlnCompletionReopenReceiptEntity::getMesOutboxId, proof.getOutboxId())
                            .eq(PlnCompletionReopenReceiptEntity::getIssueId, issue.getId()), false);
            if (existing != null) {
                require(Objects.equals(existing.getInboxId(), event.getId()),
                        "MES完工重新打开来源已被其他PLAN事件占用");
                receipts.add(existing);
                continue;
            }
            Object completionReference = StrUtil.isBlank(proof.getApsBatchNo())
                    ? issue.getIssueNo() : issue.getId();
            String completionRequestId = "MES:" + completionSource + ":COMPLETE_V"
                    + proof.getCompletionVersionAfter() + ':' + completionReference;
            PlnIssueCallbackReceiptEntity newerCompletion = callbackReceiptMapper.selectOne(
                    new LambdaQueryWrapper<PlnIssueCallbackReceiptEntity>()
                            .eq(PlnIssueCallbackReceiptEntity::getRequestId, completionRequestId), false);
            String beforeStatus = issue.getStatus();
            LocalDate finishDateBefore = issue.getFinishTime();
            require(List.of("2", "3").contains(beforeStatus),
                    "PLAN来源计划已离开可重新打开状态：" + issue.getIssueNo() + "/" + beforeStatus);
            String result;
            if (newerCompletion == null) {
                issue.setStatus("2");
                issue.setFinishTime(null);
                require(issueMapper.updateById(issue) == 1, "PLAN来源计划重新打开失败：" + issue.getIssueNo());
                result = "APPLIED";
            } else {
                result = "SUPERSEDED";
            }
            PlnCompletionReopenReceiptEntity receipt = new PlnCompletionReopenReceiptEntity()
                    .setInboxId(event.getId()).setMesOutboxId(proof.getOutboxId())
                    .setReverseRequestId(proof.getReverseRequestId()).setMesTaskId(proof.getTaskId())
                    .setMesTaskCode(proof.getTaskCode()).setCompletionVersion(proof.getCompletionVersionAfter())
                    .setIssueId(issue.getId()).setIssueNo(issue.getIssueNo()).setStatusBefore(beforeStatus)
                    .setStatusAfter("APPLIED".equals(result) ? "2" : issue.getStatus())
                    .setFinishDateBefore(finishDateBefore).setAllocatedQuantity(line.getAllocatedQuantity())
                    .setCompletedQuantity(line.getCompletedQuantity()).setApplyResult(result)
                    .setSourceFingerprint(proof.getPayloadFingerprint()).setAppliedTime(LocalDateTime.now())
                    .setFactoryCode(event.getFactoryCode());
            receipt.setTenantId(event.getTenantId());
            require(receiptMapper.insert(receipt) == 1, "PLAN完工重新打开回执保存失败");
            receipts.add(receipt);
        }
        long applied = receipts.stream().filter(row -> "APPLIED".equals(row.getApplyResult())).count();
        return new ApplyResult(List.copyOf(receipts), applied,
                "已核验MES报工冲销及来源完成量，重新打开" + applied + "张PLAN来源计划；"
                        + (receipts.size() - applied) + "张已被同版本重新完工覆盖");
    }

    private PlnIssueEntity lockIssue(PlnAutomationEventEntity event,
                                     MesPlanCompletionReopenEvidence proof,
                                     MesPlanCompletionReopenEvidence.SourceLine line) {
        LambdaQueryWrapper<PlnIssueEntity> query = new LambdaQueryWrapper<PlnIssueEntity>()
                .eq(PlnIssueEntity::getFactoryCode, event.getFactoryCode())
                .eq(PlnIssueEntity::getProductCode, proof.getProductCode());
        if (line.getIssueId() != null) query.eq(PlnIssueEntity::getId, line.getIssueId());
        else query.eq(PlnIssueEntity::getIssueNo, line.getIssueNo());
        PlnIssueEntity issue = issueMapper.selectOne(query.last("FOR UPDATE"));
        require(issue != null && Objects.equals(issue.getTenantId(), event.getTenantId())
                        && Objects.equals(issue.getIssueNo(), line.getIssueNo())
                        && issue.getNum() != null
                        && issue.getNum().compareTo(line.getAllocatedQuantity()) == 0,
                "PLAN来源计划不存在，或租户、工厂、产品、来源分配量不一致：" + line.getIssueNo());
        return issue;
    }

    private void validateLine(MesPlanCompletionReopenEvidence.SourceLine line) {
        BigDecimal allocated = line == null ? null : line.getAllocatedQuantity();
        BigDecimal completed = line == null ? null : line.getCompletedQuantity();
        require(line != null && StrUtil.isNotBlank(line.getIssueNo()) && allocated != null
                        && completed != null && allocated.signum() > 0 && completed.signum() >= 0
                        && completed.compareTo(allocated) < 0,
                "MES完工重新打开逐计划数量无效");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    public record ApplyResult(List<PlnCompletionReopenReceiptEntity> receipts,
                              long appliedCount, String summary) {
    }
}
