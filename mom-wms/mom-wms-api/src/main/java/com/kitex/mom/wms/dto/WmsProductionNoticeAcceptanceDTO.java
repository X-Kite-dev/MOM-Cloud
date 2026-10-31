package com.kitex.mom.wms.dto;

import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 受理、当前执行状态和实际出库是不同证据，不可混用。
 */
public final class WmsProductionNoticeAcceptanceDTO {
    private WmsProductionNoticeAcceptanceDTO() {
    }

    @Data
    public static class Command {
        private String requestId;
        private Long tenantId;
        private String factoryCode;
        private Integer sourceRevision;
        private Integer expectedPreviousRevision;
        private WmsOutNoticeEntity notice;
    }

    @Data
    public static class Cancel {
        private String requestId;
        private Long tenantId;
        private String factoryCode;
        private Long documentId;
        private Integer sourceRevision;
        private String reason;
    }

    public record Line(Long sourceDocumentDetailId, Long noticeDetailId, String materialCode, String unit,
                       BigDecimal quantity) {
    }

    public record Receipt(int contractVersion, String action, String status, String requestId, String originalRequestId,
                          Long tenantId, String factoryCode, String documentTable, Long documentId, int sourceRevision,
                          String warehouseCode,
                          Long noticeId, List<Line> lines, String contentFingerprint, String requestPayload,
                          LocalDateTime acceptedTime,
                          String acceptedBy, List<Long> cancelledNoticeIds, String reason) {
    }

    public record Lookup(int contractVersion, Long tenantId, String factoryCode, String requestId, String status,
                         Receipt receipt) {
    }

    public record Source(int contractVersion, Long tenantId, String factoryCode, Long documentId, int requestedRevision,
                         Integer currentRevision, String sourceStatus, String warehouseCode, String acceptanceStatus,
                         Receipt acceptance, String noticeState, List<Long> legacyNoticeIds) {
    }
}
