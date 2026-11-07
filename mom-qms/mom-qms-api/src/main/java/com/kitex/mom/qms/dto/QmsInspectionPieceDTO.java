package com.kitex.mom.qms.dto;

import java.util.List;

/** 现场登记的逐件全检；字符串编号与十进制原文，不认证 MES 实物或首次检验资格。 */
public final class QmsInspectionPieceDTO {
    private QmsInspectionPieceDTO() {}

    public record Result(String detailId, String valueText, Boolean qualified) {}

    public record Command(
            String requestId, String taskId, String identifier, List<Result> results) {
        public Command {
            results = results == null ? null : List.copyOf(results);
        }
    }

    public record Receipt(
            String state,
            String tenantId,
            String factoryCode,
            String actorId,
            String fingerprint,
            Command command,
            String pieceId,
            String occurredAt) {}

    public record Detail(
            String id,
            String name,
            String demand,
            String method,
            String position,
            String checkType,
            String lowerLimit,
            String upperLimit) {}

    public record Sample(String detailId, String recordId, String valueText, Boolean qualified) {}

    public record Piece(
            String id,
            String identifier,
            String actorId,
            String actorName,
            String occurredAt,
            List<Sample> samples) {}

    public record View(
            String availability,
            String taskId,
            String tenantId,
            String factoryCode,
            String taskStatus,
            String taskResult,
            String expectedPieces,
            String collectedPieces,
            int page,
            List<Detail> details,
            List<Piece> pieces,
            String evidenceKind,
            boolean firstPassEligible) {}
}
