package com.kitex.mom.mes.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * PLAN按MES Outbox ID重采不可变复工来源的内网证据。
 */
@Data
public class MesRepairResumeEvidence {
    private Long tenantId;
    private String factoryCode;
    private Long outboxId;
    private String eventId;
    private Long stopId;
    private Long releaseId;
    private Long arrangeId;
    private Long segmentId;
    private Long recoveryReceiptId;
    private String planNo;
    private String routeCode;
    private String deviceCode;
    private LocalDateTime resumedTime;
    private String payloadFingerprint;
    private Boolean sourceValid;
}
