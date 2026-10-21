package com.kitex.mom.wms.agent.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * WMS 库存健康聚合查询的数据库投影，仅在 WMS 领域内部使用。
 */
public final class WmsInventoryHealthRow {

    private WmsInventoryHealthRow() {
    }

    @Data
    public static class Summary {
        private Long stockRowCount;
        private Long materialCount;
        private Long warehouseCount;
        private Long lockedRowCount;
        private Long qualityRestrictedRowCount;
        private Long expiredRowCount;
        private Long nearExpiryRowCount;
        private Long slowMovingRowCount;
        private Long negativeRowCount;
        private Long overReservedRowCount;
        private Long missingUnitRowCount;
        private Long missingLocationRowCount;
    }

    @Data
    public static class Unit {
        private String unitCode;
        private Long stockRowCount;
        private BigDecimal physicalNum;
        private BigDecimal usablePhysicalNum;
        private BigDecimal productionReservedNum;
        private BigDecimal salesReservedNum;
        private BigDecimal availableNum;
        private BigDecimal lockedNum;
        private BigDecimal qualityRestrictedNum;
        private BigDecimal expiredNum;
        private BigDecimal nearExpiryNum;
        private BigDecimal slowMovingNum;
    }

    @Data
    public static class ExceptionStock {
        private Long stockId;
        private String warehouseCode;
        private String warehouseName;
        private String siteCode;
        private String siteName;
        private String matCode;
        private String matName;
        private String matBatch;
        private String matUnit;
        private BigDecimal physicalNum;
        private BigDecimal productionReservedNum;
        private BigDecimal salesReservedNum;
        private BigDecimal availableNum;
        private String qualityStatus;
        private String qualityResult;
        private Integer lockStatus;
        private LocalDateTime inTime;
        private LocalDate validEndDate;
        private String exceptionCodes;
    }
}
