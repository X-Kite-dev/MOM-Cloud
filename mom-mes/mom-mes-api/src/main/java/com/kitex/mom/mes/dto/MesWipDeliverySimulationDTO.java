package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kitex.mom.mdm.dto.MdmReceivingLocationDTO.Reference;

import java.util.List;

public final class MesWipDeliverySimulationDTO {
    private MesWipDeliverySimulationDTO() {}

    public static final String SCHEMA = "MES_WIP_DELIVERY_SIM@1";
    public static final String REDIRECTED_SCHEMA = "MES_WIP_DELIVERY_SIM@2";

    public record Command(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String requestId,
            String taskId,
            String kind,
            String transferId,
            String expectedRevision,
            String reason) {}

    public record Cancel(Command original, String reason) {}

    public record Cancellation(String reason, String canceledAt) {}

    public record RouteTarget(
            String schemaVersion,
            String dispatchOperationId,
            String dispatchFingerprint,
            MesWipDispatchTargetDTO.Selection selection,
            Reference reference,
            String observedAt) {}

    public record CreationCheck(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String transferId,
            String transferRevision,
            String arrangeId,
            String nodeCode,
            String worksiteCode,
            String locationId,
            String bindingRevision,
            String referenceHash,
            String plannedQuantity,
            String completedQuantity,
            String maximumReportQuantity,
            String remainingReportQuantity,
            String deliveryQuantity,
            String unit,
            boolean overReportAllowed,
            String overReportRate,
            String observedAt,
            boolean reserved,
            boolean productionRelease) {}

    public record Snapshot(
            String transferId,
            String transferRevision,
            String sourceStockId,
            String sourceReportId,
            String arrangeCode,
            String orderCode,
            String matCode,
            String matName,
            String matBatch,
            String barcode,
            String unit,
            String quantity,
            String fromLocationCode,
            String toLocationCode,
            @JsonInclude(JsonInclude.Include.NON_NULL) RouteTarget routeTarget,
            @JsonInclude(JsonInclude.Include.NON_NULL) CreationCheck creationCheck,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    MesWipRedirectedDeliveryDTO.Target redirectedTarget) {
        public Snapshot(
                String transferId,
                String transferRevision,
                String sourceStockId,
                String sourceReportId,
                String arrangeCode,
                String orderCode,
                String matCode,
                String matName,
                String matBatch,
                String barcode,
                String unit,
                String quantity,
                String fromLocationCode,
                String toLocationCode,
                RouteTarget routeTarget,
                CreationCheck creationCheck) {
            this(
                    transferId,
                    transferRevision,
                    sourceStockId,
                    sourceReportId,
                    arrangeCode,
                    orderCode,
                    matCode,
                    matName,
                    matBatch,
                    barcode,
                    unit,
                    quantity,
                    fromLocationCode,
                    toLocationCode,
                    routeTarget,
                    creationCheck,
                    null);
        }
    }

    public record CurrentTarget(
            String mode, String code, String activationRequestId, boolean ready) {}

    public record PhysicalTransfer(
            String transferId,
            String revision,
            String status,
            String remainingQuantity,
            String receivedQuantity,
            String unit,
            @JsonInclude(JsonInclude.Include.NON_NULL) CurrentTarget currentTarget) {
        public PhysicalTransfer(
                String transferId,
                String revision,
                String status,
                String remainingQuantity,
                String receivedQuantity,
                String unit) {
            this(transferId, revision, status, remainingQuantity, receivedQuantity, unit, null);
        }
    }

    public record Brief(String taskId, String createdAt, String reason) {}

    public record Overview(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            boolean enabled,
            boolean ready,
            boolean canWrite,
            PhysicalTransfer physical,
            List<Brief> tasks) {
        public Overview {
            tasks = List.copyOf(tasks);
        }
    }

    public record Event(
            String taskId,
            String transferId,
            String requestId,
            String sequenceNo,
            String kind,
            String reason,
            String createdAt,
            String phase,
            boolean unknown,
            boolean cancelRequested) {}

    public record Task(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String taskId,
            String mode,
            Snapshot snapshot,
            String revision,
            String phase,
            boolean unknown,
            boolean cancelRequested,
            String status,
            List<Event> events) {
        public Task {
            events = List.copyOf(events);
        }
    }

    public record Receipt(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String requestId,
            String commandFingerprint,
            String state,
            Event event,
            Cancellation cancellation) {}
}
