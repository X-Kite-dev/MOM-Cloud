package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;
import lombok.experimental.Accessors;
import java.time.LocalDateTime;
import java.util.List;

/** Stored inspection-generation and latest synchronization facts, not a live release allowance. */
public final class MesInspectionRecordWorkbenchDTO {
    private MesInspectionRecordWorkbenchDTO() {}
    @Data @Accessors(chain = true)
    public static class Query {
        private Integer current = 1;
        private Integer size = 20;
        private String taskCode;
        private String produceTaskCode;
        private String inspectionType;
        private String gateStatus;
        private String generationStatus = "ALL";
        @JsonAnySetter public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException("质检记录不支持筛选条件：" + field);
        }
    }
    public record Row(
            String id,
            String tenantId,
            String factoryCode,
            String taskCode,
            String produceTaskCode,
            String ruleCode,
            String ruleName,
            String inspectionId,
            String inspectionType,
            String sourceRequestId,
            String genFlag,
            String genRemark,
            String genTime,
            String inspectionStatus,
            String inspectionResult,
            String gateStatus,
            String qmsTotalNum,
            String qmsUsableNum,
            String qmsHandleNum,
            String qmsHandleResult,
            String blockedCount,
            String syncMessage,
            String lastSyncTime,
            String lastBlockedTime) {}
    public record PageView(String contract, String tenantId, String factoryCode, String actorUserId,
                           LocalDateTime observedAt, Query query, String total, List<Row> rows) {}
    public record Detail(String contract, String tenantId, String factoryCode, String actorUserId,
                         LocalDateTime observedAt, Row record) {}
}
