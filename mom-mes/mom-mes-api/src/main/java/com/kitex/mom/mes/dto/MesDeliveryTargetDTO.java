package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.kitex.mom.common.jackson.StrictJsonTextDeserializer;
import com.kitex.mom.mdm.dto.MdmReceivingLocationDTO.RuntimeBinding;

import java.util.List;

public final class MesDeliveryTargetDTO {
    private MesDeliveryTargetDTO() {}

    public static final String SCHEMA = "MES_WIP_TARGET_LOCATION@1";

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
            @JsonDeserialize(using = StrictJsonTextDeserializer.class) String referenceHash) {}

    public record Option(RuntimeBinding binding, boolean matchesOriginalTarget) {}

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
            List<Option> options,
            String observedAt) {
        public Options {
            options = List.copyOf(options);
        }
    }

    public record Match(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            Choice selection,
            String state,
            boolean productionRelease,
            String observedAt) {}
}
