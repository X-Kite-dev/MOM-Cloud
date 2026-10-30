package com.kitex.mom.qms.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Date;

public record QmsInspectionRevisionRunDTO(
        String contractVersion,
        String tenantId,
        String factoryCode,
        String actorId,
        String taskId,
        String requestId,
        String phase,
        String status,
        boolean resultChanged,
        Boolean held,
        boolean canAbort,
        int attemptCount,
        String message,
        String lastError,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC") Date nextAttemptAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC") Date updatedAt,
        String eventId,
        String outcomeFingerprint) {}
