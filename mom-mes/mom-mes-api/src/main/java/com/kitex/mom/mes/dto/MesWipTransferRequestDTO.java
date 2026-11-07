package com.kitex.mom.mes.dto;

import java.util.List;

public final class MesWipTransferRequestDTO {
    private MesWipTransferRequestDTO() {}

    public static final String SCHEMA = "MES_WIP_TRANSFER_REQUEST@1";
    public static final String BOUND_SCHEMA = "MES_WIP_TRANSFER_REQUEST@2";

    public record Envelope(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            MesWipTransferCommand command) {}

    public record Cancel(Envelope original, String reason) {}

    public record Context(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            List<String> allowedOperations,
            boolean cancellationReady,
            boolean canWrite) {
        public Context {
            allowedOperations = List.copyOf(allowedOperations);
        }
    }

    public record Operation(
            String id,
            String transferId,
            String sequenceNo,
            String quantity,
            String beforeRemaining,
            String afterRemaining,
            String beforeStatus,
            String afterStatus,
            String resultStockId,
            String sourceBeforeNum,
            String sourceAfterNum,
            String reason,
            String performedAt) {}

    public record Cancellation(String reason, String canceledAt) {}

    public record Receipt(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String requestId,
            String operationType,
            String state,
            String commandFingerprint,
            Operation operation,
            Cancellation cancellation,
            MesWipDispatchTargetDTO.Selection dispatchTarget) {}
}
