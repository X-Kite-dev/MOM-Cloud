package com.kitex.mom.wms.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 单条库存行的正式质量处置账与当前库存状态，只用于效果核验，不执行库存变更。
 */
public final class WmsStockDispositionReceiptDTO {

    private WmsStockDispositionReceiptDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        @NotBlank
        private String factoryCode;
        @NotBlank
        @Pattern(regexp = "[1-9][0-9]{0,18}")
        private String stockId;
        @Min(1)
        @Max(3650)
        private Integer nearExpiryDays = 30;
        @Min(1)
        @Max(3650)
        private Integer slowMovingDays = 90;
        /** 原调查观察时间；提供后才追溯该时刻之后的库存拆分子行。 */
        private LocalDateTime lineageFrom;
    }

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        private String factoryCode;
        private String stockId;
        private LocalDateTime observedAt;
        private Boolean stockPresent;
        /** WMS实物执行软归档的原库存行；仅原活动行不存在时回传。 */
        private ArchivedStock archivedOriginalStock;
        private LocalDateTime currentStockUpdatedAt;
        private BigDecimal currentPhysicalNum;
        private BigDecimal currentProductionReservedNum;
        private BigDecimal currentSalesReservedNum;
        private BigDecimal currentAvailableNum;
        private String currentMaterialCode;
        private String currentMaterialBatch;
        private String currentMaterialBarcode;
        private String currentSupplierCode;
        private String currentWarehouseCode;
        private String currentSiteCode;
        private String currentContainerCode;
        private String currentUnitCode;
        private String currentQualityStatus;
        private String currentQualityHoldNo;
        private String currentQualitySourceType;
        private String currentQualitySourceId;
        private Integer currentLockStatus;
        private List<String> currentExceptionTypes = new ArrayList<>();
        private List<QualityOperation> qualityOperations = new ArrayList<>();
        private Boolean receiptHistoryTruncated;
        /** 由正式质量事务记录的拆分余量库存行，按主键去重后展开。 */
        private List<Evidence> splitDescendants = new ArrayList<>();
        private Boolean splitLineageTruncated;
        private String splitLineageIssue;
        private List<String> evidenceRefs = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class ArchivedStock {
        private Long stockId;
        private String factoryCode;
        private BigDecimal physicalNum;
        private String materialCode;
        private String materialBatch;
        private String materialBarcode;
        private String supplierCode;
        private String warehouseCode;
        private String siteCode;
        private String containerCode;
        private String unitCode;
        private Integer lockStatus;
        private String qualityStatus;
        private String qualityHoldNo;
        private String qualitySourceType;
        private String qualitySourceId;
        private LocalDateTime stockUpdatedAt;
    }

    @Data
    @Accessors(chain = true)
    public static class QualityOperation {
        private String operationId;
        private String requestId;
        private String holdNo;
        private String sourceType;
        private String sourceId;
        private String action;
        private String status;
        private BigDecimal requestedQuantity;
        private BigDecimal affectedQuantity;
        private String affectedStockIds;
        private Boolean splitRecorded;
        private String splitRemainderStockIds;
        private String splitLineageJson;
        private LocalDateTime splitRecordedAt;
        private String executionRef;
        private String executedBy;
        private LocalDateTime executedTime;
        private LocalDateTime createdTime;
        private LocalDateTime updatedTime;
    }
}
