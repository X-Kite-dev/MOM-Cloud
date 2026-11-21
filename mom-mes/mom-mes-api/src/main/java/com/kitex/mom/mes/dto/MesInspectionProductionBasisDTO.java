package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;

import java.util.List;

/** Immutable evidence for inspection birth; no per-piece first-pass certification. */
public final class MesInspectionProductionBasisDTO {
    private MesInspectionProductionBasisDTO() {}

    public static final String SCHEMA = "MES_INSPECTION_PRODUCTION_BASIS@1";

    /** 内部核对请求。命令原文仅用于精确比对和摘要校验，从不作为页面文本返回。 */
    public record Query(
            String requestId,
            String operationCode,
            @JsonDeserialize(using = StringDeserializer.class) String commandSnapshot,
            String commandFingerprint) {}

    public record Evidence(
            String schemaVersion,
            String availability,
            String tenantId,
            String factoryCode,
            String requestId,
            String commandFingerprint,
            String operationCode,
            String orderCode,
            String orderId,
            String productCode,
            String processCode,
            String inspectionQuantity,
            String engineeringReleaseId,
            String engineeringVersion,
            String engineeringHash,
            String routeNodeCode,
            String bomId,
            String bomVersion,
            String quantityUnit,
            List<String> dataGaps) {
        public Evidence {
            dataGaps = dataGaps == null ? null : List.copyOf(dataGaps);
        }
    }
}
