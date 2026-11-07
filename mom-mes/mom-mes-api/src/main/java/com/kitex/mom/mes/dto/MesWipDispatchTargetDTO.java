package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.kitex.mom.common.jackson.StrictJsonTextDeserializer;
import com.kitex.mom.mdm.dto.MdmReceivingLocationDTO.Reference;
import com.kitex.mom.mdm.dto.MdmReceivingLocationDTO.RuntimeBinding;

import lombok.Data;

import java.util.List;

/** DISPATCH可选的原冻结后继与位置引用；无字段时维持旧V1命令原文。 */
public final class MesWipDispatchTargetDTO {
    private MesWipDispatchTargetDTO() {}

    public static final String SCHEMA = "MES_WIP_DISPATCH_TARGET@1";

    @Data
    public static class Selection {
        @JsonDeserialize(using = StrictJsonTextDeserializer.class)
        private String schemaVersion;

        @JsonDeserialize(using = StrictJsonTextDeserializer.class)
        private String tenantId;

        @JsonDeserialize(using = StrictJsonTextDeserializer.class)
        private String factoryCode;

        @JsonDeserialize(using = StrictJsonTextDeserializer.class)
        private String actorId;

        @JsonDeserialize(using = StrictJsonTextDeserializer.class)
        private String sourceStockId;

        @JsonDeserialize(using = StrictJsonTextDeserializer.class)
        private String sourceReportId;

        @JsonDeserialize(using = StrictJsonTextDeserializer.class)
        private String arrangeId;

        @JsonDeserialize(using = StrictJsonTextDeserializer.class)
        private String nodeCode;

        @JsonDeserialize(using = StrictJsonTextDeserializer.class)
        private String worksiteCode;

        @JsonDeserialize(using = StrictJsonTextDeserializer.class)
        private String locationId;

        @JsonDeserialize(using = StrictJsonTextDeserializer.class)
        private String bindingRevision;

        @JsonDeserialize(using = StrictJsonTextDeserializer.class)
        private String referenceHash;
    }

    public record Options(
            String schemaVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String sourceStockId,
            String sourceReportId,
            String arrangeId,
            String nodeCode,
            String worksiteCode,
            List<RuntimeBinding> options,
            String observedAt) {
        public Options {
            options = List.copyOf(options);
        }
    }

    public record Validated(Selection selection, Reference reference, String observedAt) {}
}
