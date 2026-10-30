package com.kitex.mom.mes.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesQualityGateDecision;
import com.kitex.mom.mes.dto.MesQualityGateItemDTO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 生成可跨报工、成品入库和WMS实物持续传递的QMS判定证据。
 *
 * <p>证据只记录业务发生时实际参与质量门判定的任务，不在之后按物料、批次或时间 重新猜测。质量免检场景同样形成明确的空任务证据，避免把“无需质检”和 “历史来源缺失”混为一谈。
 */
public final class MesQualityEvidenceSupport {

    private static final int MAX_QUALITY_TASK_COUNT = 1000;
    private static final int MAX_EVIDENCE_BYTES = 1024 * 1024;

    private MesQualityEvidenceSupport() {
    }

    /**
     * 将一次质量门判定规范化为稳定快照和SHA-256指纹。
     */
    public static Evidence capture(MesQualityGateDecision decision) {
        if (decision == null || StrUtil.isBlank(decision.getGateMode())) {
            throw new CheckedException("质量门判定缺少模式，不能冻结质量证据");
        }
        List<MesQualityGateItemDTO> items =
                new ArrayList<>(decision.getItems() == null ? List.of() : decision.getItems());
        items.sort(
                Comparator.comparing(
                                MesQualityGateItemDTO::getRecordId,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(
                                MesQualityGateItemDTO::getInspectionId,
                                Comparator.nullsLast(Comparator.naturalOrder())));

        LinkedHashSet<Long> taskIds = new LinkedHashSet<>();
        List<Map<String, Object>> taskFacts = new ArrayList<>();
        for (MesQualityGateItemDTO item : items) {
            if (item.getInspectionId() == null) {
                if (!"NONE".equals(decision.getGateMode())
                        && Boolean.TRUE.equals(decision.getPassed())) {
                    throw new CheckedException("质量门存在未生成QMS任务的证据，不能形成可逆向来源");
                }
                continue;
            }
            taskIds.add(item.getInspectionId());
            if (taskIds.size() > MAX_QUALITY_TASK_COUNT) {
                throw new CheckedException("单次报工关联QMS任务超过1000条，请拆分报工后重试");
            }
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("recordId", item.getRecordId());
            fact.put("inspectionId", item.getInspectionId());
            fact.put("sourceRequestId", item.getSourceRequestId());
            fact.put("taskCode", item.getTaskCode());
            fact.put("produceTaskCode", item.getProduceTaskCode());
            fact.put("inspectionType", item.getInspectionType());
            fact.put("inspectionStatus", item.getInspectionStatus());
            fact.put("inspectionResult", item.getInspectionResult());
            fact.put("gateStatus", item.getGateStatus());
            fact.put("qmsTotalNum", item.getQmsTotalNum());
            fact.put("qmsUsableNum", item.getQmsUsableNum());
            fact.put("qmsHandleNum", item.getQmsHandleNum());
            fact.put("qmsHandleResult", item.getQmsHandleResult());
            fact.put("lastSyncTime", item.getLastSyncTime());
            if (item.getQualityEventState() != null) {
                fact.put("qualityEventId", item.getQualityEventId());
                fact.put("qualityEventSequence", item.getQualityEventSequence());
                fact.put("qualityEventFingerprint", item.getQualityEventFingerprint());
                fact.put("qualityEventVersion", item.getQualityEventVersion());
                fact.put("qualityEventState", item.getQualityEventState());
                fact.put("qualityBlockedEventId", item.getQualityBlockedEventId());
            }
            taskFacts.add(fact);
        }
        if (!"NONE".equals(decision.getGateMode())
                && Boolean.TRUE.equals(decision.getPassed())
                && !items.isEmpty()
                && taskIds.isEmpty()) {
            throw new CheckedException("质量门未关联具体QMS任务，不能形成可逆向质量来源");
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put(
                "version",
                items.stream().anyMatch(i -> i.getQualityEventState() != null)
                        ? "MES_QMS_EVIDENCE_V2"
                        : "MES_QMS_EVIDENCE_V1");
        snapshot.put("gateMode", decision.getGateMode());
        snapshot.put("phase", decision.getPhase());
        snapshot.put("passed", decision.getPassed());
        snapshot.put("message", decision.getMessage());
        snapshot.put("tasks", taskFacts);
        String snapshotJson = JSONUtil.toJsonStr(snapshot);
        if (snapshotJson.getBytes(StandardCharsets.UTF_8).length > MAX_EVIDENCE_BYTES) {
            throw new CheckedException("单次报工质量证据超过1MB，请拆分报工后重试");
        }
        return new Evidence(JSONUtil.toJsonStr(taskIds), sha256(snapshotJson), snapshotJson);
    }

    private static String sha256(String value) {
        try {
            byte[] bytes =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前JVM不支持SHA-256", exception);
        }
    }

    /**
     * 规范化后的质量任务集合、指纹和完整快照。
     */
    public record Evidence(String taskIdsJson, String fingerprint, String snapshotJson) {
    }
}
