package com.kitex.mom.pms.agent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 供应商来料质量改善场景使用的PMS/MDM权威对象快照。
 */
public final class PmsSupplierQualitySourceDTO {

    private PmsSupplierQualitySourceDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        @NotBlank
        private String factoryCode;
        @NotBlank
        private String supplierMaterialId;
    }

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String factoryCode;
        private String supplierMaterialId;
        private String supplierId;
        private String supplierCode;
        private String supplierName;
        private String supplierClassCode;
        private String supplierClassName;
        private Boolean supplierEnabled;
        private String supplierCheckStatus;
        private Boolean supplierDeliverable;
        private String materialCode;
        private String materialName;
        private String materialSpec;
        private String materialUnit;
        private Boolean inspectionExempt;
        private String currentRating;
        private BigDecimal currentScore;
        private LocalDateTime observedAt;
        private List<String> dataGaps;
        private List<String> limitations;
        private List<String> evidenceRefs;
        private String factFingerprint;
    }
}
