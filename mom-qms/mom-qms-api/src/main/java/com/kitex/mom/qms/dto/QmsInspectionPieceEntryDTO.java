package com.kitex.mom.qms.dto;

public final class QmsInspectionPieceEntryDTO {
    private QmsInspectionPieceEntryDTO() {}

    public static final String SCHEMA = "QMS_INSPECTION_PIECE_ENTRY@1";

    public record Entry(
            String sessionId,
            String taskCode,
            String produceTaskCode,
            String sourceRouteNodeCode,
            String workerCode,
            String worksiteCode,
            String requestId,
            String capturedAt,
            String fingerprint) {}

    public record View(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String taskId,
            String pieceId,
            String identifier,
            String basisFingerprint,
            String availability,
            Entry entry,
            boolean firstPassEligible,
            String evidenceTiming,
            String evidenceCapturedAt) {}
}
