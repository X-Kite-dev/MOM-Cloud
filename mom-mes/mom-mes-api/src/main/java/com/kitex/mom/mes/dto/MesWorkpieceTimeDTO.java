package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.kitex.mom.mes.entity.MesWorkpieceTimeIntervalEntity;
import com.kitex.mom.mes.entity.MesWorkpieceTimeSessionEntity;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 现场工件计时不申报产量；标识依据始终为操作者观察，不能冒充实物绑定已核验。
 */
public final class MesWorkpieceTimeDTO {

    private MesWorkpieceTimeDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Command {
        private String requestId;
        private String arrangeId;
        private String action;
        private String identifierType;
        private String identifier;
        private Long expectedVersion;
        private String reasonCode;

        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException("工件计时不支持字段：" + field);
        }
    }

    public record Receipt(String contractVersion, String requestId, String action, String sessionId,
                          String arrangeId, String identifierType, String identifier, String status, String pauseSource,
                          long versionNo, LocalDateTime capturedAt, String captureBasis) {
    }

    public record Resolution(String contractVersion, String requestId, String state, Receipt receipt) {
    }

    /** 来源是实际START留存；人工标识仍不是实物认证，不提供首次合格资格。 */
    public record EntrySource(String sessionId, String taskCode, String produceTaskCode,
                              String sourceRouteNodeCode, String workerCode, String worksiteCode,
                              String requestId, String capturedAt, String fingerprint) {}

    public record EntrySources(String availability, List<EntrySource> entries) {}

    public record View(String tenantId, String factoryCode, String arrangeId, String captureBasis, String notice,
                       List<MesWorkpieceTimeSessionEntity> sessions,
                       List<MesWorkpieceTimeIntervalEntity> intervals, int sessionPage, int sessionPageSize,
                       long sessionTotal,
                       int intervalPage, int intervalPageSize, long intervalTotal, EntrySources entrySources) {
    }
}
