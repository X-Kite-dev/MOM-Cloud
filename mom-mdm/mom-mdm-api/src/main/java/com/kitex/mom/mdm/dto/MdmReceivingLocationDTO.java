package com.kitex.mom.mdm.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.kitex.mom.common.jackson.StrictJsonTextDeserializer;

import java.util.List;

public final class MdmReceivingLocationDTO {
    private MdmReceivingLocationDTO() {}

    public static final String SCHEMA = "MDM_STATION_RECEIVING@1";

    public record Station(
            String id,
            String code,
            String name,
            String lineId,
            String lineCode,
            String workshopId,
            String workshopCode,
            String factoryId,
            String industryId,
            boolean valid) {}

    public record Point(
            String id,
            String code,
            String name,
            String warehouseId,
            String warehouseCode,
            String warehouseName,
            String areaId,
            String shelfId,
            boolean valid) {}

    public record Reference(Station station, Point point, String hash) {}

    public record Command(
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String schemaVersion,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String tenantId,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String factoryCode,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String actorId,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String requestId,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String stationId,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String locationId,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String expectedRevision,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String state,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String referenceHash,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String reason) {}

    public record Cancel(Command original, String reason) {}

    public record Change(
            String stationId,
            String locationId,
            String revision,
            String state,
            Reference reference,
            String reason,
            String actorId,
            String recordedAt) {}

    public record Cancellation(String reason, String canceledAt) {}

    public record Receipt(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String requestId,
            String fingerprint,
            String outcome,
            Change change,
            Cancellation cancellation) {}

    public record Binding(Change change, Reference current, String issue) {}

    public record Overview(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            Station station,
            boolean ready,
            boolean canWrite,
            List<Binding> bindings) {
        public Overview {
            bindings = List.copyOf(bindings);
        }
    }

    public record ReferenceView(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            Reference reference) {}

    public record Points(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            Station station,
            String search,
            String afterId,
            List<Point> points,
            String nextAfterId) {
        public Points {
            points = List.copyOf(points);
        }
    }

    public record RuntimeBinding(
            String stationId, String locationId, String revision, Reference reference) {}

    public record RuntimeView(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            Station station,
            boolean ready,
            List<RuntimeBinding> bindings) {
        public RuntimeView {
            bindings = List.copyOf(bindings);
        }
    }

    public record History(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String stationId,
            String locationId,
            String beforeRevision,
            List<Change> changes,
            String nextBeforeRevision) {
        public History {
            changes = List.copyOf(changes);
        }
    }
}
