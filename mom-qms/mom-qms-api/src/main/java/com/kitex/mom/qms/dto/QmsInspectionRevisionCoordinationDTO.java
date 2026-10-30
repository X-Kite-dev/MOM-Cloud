package com.kitex.mom.qms.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Date;

/** held为null表示尚无当前操作的确切MES确认，不能当成未冻结。 */
public record QmsInspectionRevisionCoordinationDTO(
        String contractVersion,
        String tenantId,
        String factoryCode,
        String actorId,
        String taskId,
        String requestId,
        String intentFingerprint,
        String operation,
        String status,
        int attemptCount,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC") Date nextAttemptAt,
        String lastError,
        Boolean held,
        boolean applied,
        Receipt receipt) {
    public record Receipt(
            String status,
            String genRecordId,
            String admissionFingerprint,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC") Date reservedAt,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC") Date canceledAt,
            String canceledBy,
            String cancelReason) {}
}
