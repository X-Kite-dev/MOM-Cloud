package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.kitex.mom.common.jackson.StrictJsonTextDeserializer;
import com.kitex.mom.mes.dto.MesWipRedirectTargetDTO.*;

import java.util.List;

public final class MesWipRedirectReviewDTO {
    private MesWipRedirectReviewDTO() {}

    public static final String SCHEMA = "MES_WIP_REDIRECT_REVIEW@1";

    public record Command(
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String schemaVersion,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String tenantId,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String factoryCode,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String actorId,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String requestId,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String action,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String transferId,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String proposalId,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String expectedVersion,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String reason,
            Choice selection,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String reviewHash) {}

    public record Cancel(Command original, String reason) {}

    public record Source(
            String transferId,
            String transferRevision,
            String sourceReportId,
            String sourceStockId,
            String fromLocationCode,
            String originalLocationCode,
            String quantity,
            String unit,
            String sourceHash) {}

    public record Evidence(Source source, Check check) {}

    public record Event(
            String requestId,
            String proposalId,
            String version,
            String action,
            String state,
            String actorId,
            String actorCode,
            String reason,
            String createdAt) {}

    public record Proposal(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String proposalId,
            String transferId,
            String version,
            String state,
            String applicantId,
            String applicantCode,
            Source source,
            Check originalCheck,
            Check approvedCheck,
            List<Event> events,
            boolean transferChanged,
            boolean effective,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    MesWipRedirectActivationDTO.Effect activation) {}

    public record Receipt(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String requestId,
            String commandFingerprint,
            String state,
            Event event,
            String cancellationReason) {}

    public record Overview(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String transferId,
            boolean ready,
            boolean enabled,
            boolean canWrite,
            boolean canApply,
            boolean canReview,
            List<Proposal> proposals,
            boolean hasMore,
            boolean canActivate,
            boolean activationEnabled,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    MesWipRedirectActivationDTO.Effect activation) {}

    public record Preview(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String proposalId,
            String version,
            Check check,
            String semanticHash,
            boolean effective) {}
}
