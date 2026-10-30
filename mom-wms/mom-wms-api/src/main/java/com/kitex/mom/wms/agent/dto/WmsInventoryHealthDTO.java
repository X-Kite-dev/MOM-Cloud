package com.kitex.mom.wms.agent.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * WMS 库存数量、预留、质量、效期和库龄健康模型，供受控问数与仓库运营看板使用。
 */
public final class WmsInventoryHealthDTO {

    private WmsInventoryHealthDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "WMS 库存健康受控查询")
    public static class Query {
        @NotBlank
        private String factoryCode;
        @Schema(description = "可选的精确仓库编码；为空时按当前工厂统计")
        private String warehouseCode;
        @Min(1)
        @Max(3650)
        private Integer nearExpiryDays = 30;
        @Min(1)
        @Max(3650)
        private Integer slowMovingDays = 90;
        @Min(1)
        @Max(50)
        private Integer limit = 20;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "WMS 库存健康权威证据")
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String factoryCode;
        private String warehouseCode;
        private LocalDate observedDate;
        private LocalDateTime observedAt;
        private Integer nearExpiryDays;
        private Integer slowMovingDays;
        private Summary summary;
        private List<UnitSummary> unitSummaries = new ArrayList<>();
        private List<ExceptionStock> exceptionStocks = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> limitations = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        private Boolean truncated;
    }

    @Data
    @Accessors(chain = true)
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
        /** 至少命中一种库存健康异常的去重库存行数。 */
        private Long exceptionRowCount;
        /** 负库存、超预留、缺单位或缺库位等必须先修复的数据/执行风险行数。 */
        private Long integrityRiskRowCount;
        /** 锁定、质量限制或已过期等需要进入原 WMS/QMS 处置流程的库存行数。 */
        private Long dispositionReviewRowCount;
        /** 临期或呆滞等需要提前干预、但不等同库存错误的预警行数。 */
        private Long earlyWarningRowCount;
    }

    @Data
    @Accessors(chain = true)
    public static class UnitSummary {
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
    @Accessors(chain = true)
    public static class ExceptionStock {
        @JsonSerialize(using = ToStringSerializer.class)
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
        private List<String> exceptionTypes = new ArrayList<>();
    }
}
