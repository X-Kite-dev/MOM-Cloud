package com.kitex.mom.mes.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 开工来源协议；ID和原数量始终以文本传输，来源池不代表库存预留或实耗。
 */
public final class MesStartMaterialBindingDTO {
    private MesStartMaterialBindingDTO() {
    }

    @Data
    public static class Query {
        private String arrangeId;
        private List<String> feedIds;
    }

    @Data
    public static class Command {
        private String contractVersion;
        private String requestId;
        private String arrangeId;
        private String expectedTenantId;
        private String expectedFactoryCode;
        private String expectedActorId;
        private List<String> feedIds;
        private String expectedPreviewFingerprint;
    }

    public record Source(String feedId, String birthId, String birthFingerprint,
                         String requirementMaterialCode, String actualMaterialCode, String batchNo, String barcode,
                         String unit, String ratio, String feedQuantity, String usedQuantity) {
    }

    public record Preparation(String contractVersion, String tenantId, String factoryCode, String actorId,
                              String observedAt, String arrangeId, String arrangeCode, String taskCode,
                              String produceTaskCode,
                              String routeNodeCode, String productBatchNo, String reportMode, String consumptionMode,
                              boolean canBind, List<String> reasons, List<Source> sources) {
    }

    public record Preview(String contractVersion, String tenantId, String factoryCode, String actorId,
                          String observedAt, String arrangeId, String arrangeCode, String taskCode,
                          String produceTaskCode,
                          String routeNodeCode, String productBatchNo, String previewFingerprint,
                          List<Source> selectedSources) {
    }

    public record Receipt(String contractVersion, String tenantId, String factoryCode, String actorId,
                          String observedAt, String requestId, String state, Map<String, Object> binding) {
    }
}
