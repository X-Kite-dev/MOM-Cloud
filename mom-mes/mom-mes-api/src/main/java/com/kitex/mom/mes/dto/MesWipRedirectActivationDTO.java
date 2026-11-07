package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.dto.MesWipRedirectTargetDTO.Check;

public final class MesWipRedirectActivationDTO {
    private MesWipRedirectActivationDTO() {}

    public static final String SCHEMA = "MES_WIP_REDIRECT_ACTIVATION@1";

    public record Display(
            @com.fasterxml.jackson.annotation.JsonUnwrapped
                    com.kitex.mom.mes.entity.MesWipTransferEntity transfer,
            String effectiveToLocationCode,
            Effect redirectActivation) {}

    public record Effect(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String requestId,
            String proposalId,
            String transferId,
            String beforeRevision,
            String afterRevision,
            String actorId,
            String actorCode,
            String reason,
            String approvalHash,
            Check check,
            String createdAt) {}
}
