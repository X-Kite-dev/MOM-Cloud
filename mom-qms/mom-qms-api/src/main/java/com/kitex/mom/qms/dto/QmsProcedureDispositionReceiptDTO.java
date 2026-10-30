package com.kitex.mom.qms.dto;

public record QmsProcedureDispositionReceiptDTO(
        String contractVersion,
        String tenantId,
        String factoryCode,
        String actorId,
        String taskId,
        String requestId,
        boolean exists,
        boolean stale,
        String expectedEventId,
        String expectedFingerprint,
        Receipt receipt) {
    public record Receipt(
            String id,
            String requestId,
            Integer handleNum,
            String handleResult,
            String handleRemark,
            String authorizationRef,
            boolean concessionUnrestricted,
            String expectedEventId,
            String expectedFingerprint,
            String receiptId,
            String eventId,
            String handledBy,
            Integer handledAfter,
            Integer usableAfter,
            String resultAfter,
            String deliveryStatus) {}
}
