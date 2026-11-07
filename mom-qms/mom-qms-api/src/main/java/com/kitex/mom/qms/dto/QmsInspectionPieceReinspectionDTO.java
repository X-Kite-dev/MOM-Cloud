package com.kitex.mom.qms.dto;

import java.util.List;

/** 现有QMS样本之间的明确复检关联；不认证实物出生或首次合格资格。 */
public final class QmsInspectionPieceReinspectionDTO {
    private QmsInspectionPieceReinspectionDTO() {}

    public record Command(
            String taskId,
            String pieceId,
            String sourcePieceId,
            String coordinationId,
            String completionFingerprint,
            String sourceBasisFingerprint,
            String currentBasisFingerprint) {}

    public record Summary(
            String taskId,
            String pieceId,
            String identifier,
            String orderNo,
            String processCode,
            String basisFingerprint,
            String actorId,
            String actorName,
            String collectedAt,
            String sampleCount,
            boolean qualified) {}

    public record Proposal(Command command, Summary source, Summary current) {}

    public record Evidence(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            Command command,
            Summary source,
            Summary current,
            String sourceRelationFingerprint,
            String boundBy,
            String boundName,
            String boundAt) {}

    public record View(
            String schemaVersion,
            String availability,
            String tenantId,
            String factoryCode,
            String taskId,
            String pieceId,
            Proposal proposal,
            List<Evidence> history,
            String originQualification,
            boolean firstPassEligible) {
        public View {
            history = List.copyOf(history);
        }
    }
}
