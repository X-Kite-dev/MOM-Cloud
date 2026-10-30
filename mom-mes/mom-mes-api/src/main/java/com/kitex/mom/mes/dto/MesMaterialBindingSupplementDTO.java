package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.dto.MesStartMaterialBindingDTO.Source;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 暂停批次的追加确认；所有原数量及ID以精确文本传输。
 */
public final class MesMaterialBindingSupplementDTO {
    private MesMaterialBindingSupplementDTO() {
    }

    @Data
    public static class Query {
        private String arrangeId;
        private List<String> addedFeedIds;
    }

    @Data
    public static class Command {
        private String contractVersion;
        private String requestId;
        private String arrangeId;
        private String expectedTenantId;
        private String expectedFactoryCode;
        private String expectedActorId;
        private String expectedPoolFingerprint;
        private List<String> addedFeedIds;
        private String expectedPreviewFingerprint;
    }

    public record SourceState(String feedId, boolean isLocked, String validEndDate, boolean eligible) {
    }

    public record ExistingSource(String feedId, String birthId, String birthFingerprint,
                                 String requirementMaterialCode, String actualMaterialCode, String batchNo,
                                 String barcode,
                                 String unit, String ratio, String feedQuantity, String usedQuantity,
                                 boolean isLocked, String validEndDate, boolean eligible) {
    }

    public record Pool(String initialBindingId, String initialBindingFingerprint, String revision,
                       String fingerprint, List<String> feedIds, List<Map<String, Object>> history) {
    }

    public record Preparation(String contractVersion, String tenantId, String factoryCode, String actorId,
                              String observedAt, String arrangeId, String arrangeCode, String taskCode,
                              String produceTaskCode,
                              String routeNodeCode, String productBatchNo, boolean canSupplement, List<String> reasons,
                              List<Source> sources, Pool pool) {
    }

    public record Preview(String contractVersion, String tenantId, String factoryCode, String actorId,
                          String observedAt, String arrangeId, String arrangeCode, String taskCode,
                          String produceTaskCode,
                          String routeNodeCode, String productBatchNo, String previewFingerprint,
                          List<Source> selectedSources, Pool pool) {
    }

    public record Receipt(String contractVersion, String tenantId, String factoryCode, String actorId,
                          String observedAt, String requestId, String state, Map<String, Object> supplement) {
    }

    public record Arrangement(String contractVersion, String tenantId, String factoryCode, String actorId,
                              String observedAt, String arrangeId, Pool pool) {
    }
}
