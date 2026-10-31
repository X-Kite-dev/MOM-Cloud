package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.mom.mes.dto.MesPlanCompletionReopenEvidence;
import com.kitex.mom.mes.entity.MesPlanCompletionReopenOutboxEntity;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Objects;

import static com.kitex.mom.mes.service.impl.MesPlanCompletionCodec.require;

/**
 * 原1载荷只读兼容；新2原文明确版本、头身份和逐工单数量，不重建原摘要。
 */
final class MesCompletionReopenCodec {
    private MesCompletionReopenCodec() {
    }

    static int version(MesPlanCompletionReopenEvidence payload) {
        Integer version = payload.getSourceSchemaVersion();
        require(version == null || version == 1 || version == 2, "未知反向完成来源模型");
        return version == null ? 1 : version;
    }

    static MesPlanCompletionReopenEvidence read(
            ObjectMapper json, MesPlanCompletionReopenOutboxEntity row) {
        require(
                row != null
                        && row.getPayloadJson() != null
                        && row.getPayloadJson().length() <= 1_000_000
                        && Objects.equals(
                        MesPlanCompletionCodec.hash(row.getPayloadJson()),
                        row.getPayloadFingerprint()),
                "反向完成原文摘要不符或范围无效");
        MesPlanCompletionReopenEvidence payload;
        try {
            var model = JSONUtil.parseObj(row.getPayloadJson()).getInt("sourceSchemaVersion");
            if (model != null && model == 2)
                payload =
                        json.copy()
                                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                                .readValue(
                                        row.getPayloadJson(),
                                        MesPlanCompletionReopenEvidence.class);
            else
                payload =
                        JSONUtil.toBean(
                                row.getPayloadJson(), MesPlanCompletionReopenEvidence.class);
        } catch (Exception failure) {
            throw new com.kitex.common.core.exception.CheckedException("反向完成原件不可读取");
        }
        int version = version(payload);
        require(
                Objects.equals(payload.getTenantId(), row.getTenantId())
                        && Objects.equals(payload.getFactoryCode(), row.getFactoryCode())
                        && Objects.equals(payload.getOutboxId(), row.getId())
                        && Objects.equals(payload.getEventId(), row.getEventId())
                        && Objects.equals(payload.getReverseRequestId(), row.getReverseRequestId())
                        && Objects.equals(payload.getTaskId(), row.getTaskId())
                        && Objects.equals(payload.getTaskCode(), row.getTaskCode())
                        && Objects.equals(payload.getPlanNo(), row.getPlanNo())
                        && Objects.equals(
                        StrUtil.trimToNull(payload.getApsBatchNo()),
                        StrUtil.trimToNull(row.getApsBatchNo()))
                        && Objects.equals(payload.getProductCode(), row.getProductCode())
                        && Objects.equals(
                        payload.getCompletionVersionBefore(),
                        row.getCompletionVersionBefore())
                        && Objects.equals(
                        payload.getCompletionVersionAfter(),
                        row.getCompletionVersionAfter())
                        && MesPlanCompletionOutboxService.same(
                        payload.getCompleteQuantityBefore(),
                        row.getCompleteQuantityBefore())
                        && MesPlanCompletionOutboxService.same(
                        payload.getCompleteQuantityAfter(), row.getCompleteQuantityAfter()),
                "反向完成原件头与永久行不一致");
        require(
                row.getId() != null
                        && row.getId() > 0
                        && row.getTenantId() != null
                        && row.getTenantId() > 0
                        && row.getTaskId() != null
                        && row.getTaskId() > 0
                        && row.getReverseRequestId() != null
                        && row.getReverseRequestId() > 0
                        && StrUtil.isNotBlank(row.getFactoryCode())
                        && StrUtil.isNotBlank(row.getTaskCode())
                        && StrUtil.isNotBlank(row.getPlanNo())
                        && StrUtil.isNotBlank(row.getProductCode())
                        && row.getCompletionVersionBefore() != null
                        && row.getCompletionVersionBefore() >= 0
                        && row.getCompletionVersionAfter() != null
                        && (long) row.getCompletionVersionAfter()
                        == (long) row.getCompletionVersionBefore() + 1
                        && row.getCompleteQuantityAfter() != null
                        && row.getCompleteQuantityAfter().signum() >= 0
                        && row.getCompleteQuantityBefore() != null
                        && row.getCompleteQuantityBefore().compareTo(row.getCompleteQuantityAfter())
                        > 0,
                "反向完成工单、世代或数量无效");
        require(
                payload.getSourceLines() != null
                        && !payload.getSourceLines().isEmpty()
                        && payload.getSourceLines().size() <= 100,
                "反向完成来源范围无效");
        if (version == 2) {
            require(
                    Objects.equals(
                            row.getEventId(),
                            "MES-COMPLETION-REOPEN:"
                                    + row.getReverseRequestId()
                                    + ":V"
                                    + row.getCompletionVersionAfter())
                            && Objects.equals(payload.getReopenedTime(), row.getReopenedTime()),
                    "反向完成事件号或原时刻不符");
            var numbers = new HashSet<String>();
            var ids = new HashSet<Long>();
            BigDecimal completed = BigDecimal.ZERO;
            for (var line : payload.getSourceLines()) {
                require(
                        line != null
                                && StrUtil.isNotBlank(line.getIssueNo())
                                && numbers.add(line.getIssueNo())
                                && line.getAllocatedQuantity() != null
                                && line.getAllocatedQuantity().signum() > 0
                                && line.getCompletedQuantity() != null
                                && line.getCompletedQuantity().signum() >= 0
                                && line.getCompletedQuantity()
                                .compareTo(line.getAllocatedQuantity())
                                <= 0
                                && (StrUtil.isBlank(row.getApsBatchNo())
                                ? payload.getSourceLines().size() == 1
                                && line.getIssueId() == null
                                : line.getIssueId() != null
                                && line.getIssueId() > 0
                                && ids.add(line.getIssueId())),
                        "新反向完成来源重复、归属或逐行数量无效");
                completed = completed.add(line.getCompletedQuantity());
            }
            require(
                    MesPlanCompletionOutboxService.same(completed, row.getCompleteQuantityAfter()),
                    "新反向完成逐来源实际量不守恒");
        }
        return payload;
    }
}
