package com.kitex.mom.mes.dto;

import com.kitex.mom.mdm.dto.MdmRouteEngineeringDTO;

import java.util.List;

public final class MesManualTaskView {
    private MesManualTaskView() {
    }

    public record Candidates(
            String contractVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String productCode,
            List<MdmRouteEngineeringDTO.ProductionCandidate> candidates) {
    }

    public record Receipt(
            String contractVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String requestId,
            String state,
            boolean exists,
            MesManualTaskCommand command,
            String fingerprint,
            Task task) {
    }

    public record Task(
            String id,
            String code,
            String status,
            String delFlag,
            Integer engineeringVersionNo,
            String acceptedAt) {
    }
}
