package com.kitex.mom.mes.dto;

/** Read-only exact lookup; operator-observed SN is not physical identity certification. */
public final class MesInspectionPieceEntryDTO {
    private MesInspectionPieceEntryDTO() {}

    public static final String SCHEMA = "MES_INSPECTION_PIECE_ENTRY@1";

    public record Query(
            String requestId, String commandFingerprint, String operationCode, String identifier) {}

    public record View(
            String schemaVersion,
            String availability,
            String tenantId,
            String factoryCode,
            String identifier,
            MesInspectionProductionBasisDTO.Evidence productionBasis,
            MesWorkpieceTimeDTO.EntrySource entry) {}
}
