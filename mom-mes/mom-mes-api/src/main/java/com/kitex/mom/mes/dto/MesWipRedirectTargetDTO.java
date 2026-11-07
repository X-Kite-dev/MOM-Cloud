package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.kitex.mom.common.jackson.StrictJsonTextDeserializer;
import com.kitex.mom.mdm.dto.MdmReceivingLocationDTO.*;

import java.util.List;

/** 只读改向资格；不是审批、目标变更、库存预留或生产放行。 */
public final class MesWipRedirectTargetDTO {
    private MesWipRedirectTargetDTO() {}

    public static final String SCHEMA = "MES_WIP_REDIRECT_TARGET@1";

    public record Choice(
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String schemaVersion,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String tenantId,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String factoryCode,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String actorId,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String transferId,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String transferRevision,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String arrangeId,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String worksiteCode,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String locationId,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String bindingRevision,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String referenceHash,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String quantity,
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String unit) {}

    public record Capacity(
            String plannedQuantity,
            String completedQuantity,
            String maximumReportQuantity,
            String remainingReportQuantity,
            String deliveryQuantity,
            String unit,
            boolean overReportAllowed,
            String overReportRate) {}

    public record Option(RuntimeBinding binding, boolean eligible) {}

    public record Options(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String transferId,
            String transferRevision,
            String arrangeId,
            String nodeCode,
            String worksiteCode,
            String originalLocationCode,
            String returnLocationCode,
            String warehouseCode,
            String originalLocationId,
            String returnLocationId,
            String quantity,
            String unit,
            List<Option> options,
            String observedAt) {
        public Options {
            options = List.copyOf(options);
        }
    }

    public record Check(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            Choice selection,
            String nodeCode,
            Reference reference,
            String originalLocationCode,
            Capacity capacity,
            String state,
            String observedAt,
            boolean applied,
            boolean reserved,
            boolean productionRelease) {}
}
