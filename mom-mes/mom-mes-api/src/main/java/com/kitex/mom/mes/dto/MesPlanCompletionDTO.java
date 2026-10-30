package com.kitex.mom.mes.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 不可变工单完成原件与当前完整来源进度，二者不混作同一时间点的事实。
 */
public final class MesPlanCompletionDTO {

    private MesPlanCompletionDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Event {
        private Integer schemaVersion;
        private Long tenantId;
        private String factoryCode;
        private Long outboxId;
        private String eventId;
        private Long taskId;
        private String taskCode;
        private String productCode;
        private String apsBatchNo;
        private Integer completionVersion;
        private BigDecimal completedQuantity;
        private LocalDateTime completedAt;
        private List<Line> sources;
    }

    @Data
    @Accessors(chain = true)
    public static class Line {
        private Long issueId;
        private String issueNo;
        private BigDecimal allocatedQuantity;
        private BigDecimal completedQuantity;
    }

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        private Event event;
        private String payloadFingerprint;
        private Boolean sourceValid;
    }

    @Data
    @Accessors(chain = true)
    public static class Progress {
        private Long tenantId;
        private String factoryCode;
        private Long outboxId;
        private String payloadFingerprint;
        private Long issueId;
        private String issueNo;
        private String productCode;
        private BigDecimal allocatedQuantity;
        private BigDecimal completedQuantity;
        private Boolean allFinished;
        private Integer taskCount;
        private LocalDateTime completedAt;
        private String sourceFingerprint;
    }
}
