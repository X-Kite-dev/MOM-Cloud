package com.kitex.mom.qms.dto;

public record QmsProcedureDispositionBasisDTO(
        String contractVersion,
        String tenantId,
        String factoryCode,
        String actorId,
        String taskId,
        String mode,
        boolean eligible,
        String reason,
        Integer total,
        Integer handled,
        Integer usable,
        Integer remaining,
        String eventId,
        String fingerprint,
        String result) {}
