package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;

/**
 * 成品信息
 *
 * @author kitex
 * @since 2025-04-24
 */
@Data
public class ProductRetrospectDTO {

    // ---------- 基本信息 ----------

    @Schema(description = "产品名称")
    private String matName;

    @Schema(description = "成品批次")
    private String batchNo;

    @Schema(description = "包装规格")
    private String matSpec;

    @Schema(description = "生产数量")
    private BigDecimal num;

    // ---------- 存储位置 ----------

    private StorageLocation storageLocation;
    private StoragePackage storagePackage;


    // ---------- 包装信息 ----------
    private StorageStatus storageStatus;

    @Data
    public static class StorageLocation {

        @Schema(description = "仓库编号")
        private String warehouseCode;

        @Schema(description = "仓库名称")
        private String warehouseName;

        @Schema(description = "库位编号")
        private String locationCode;

        @Schema(description = "库位名称")
        private String locationName;

        @Schema(description = "货架层数")
        private String shelvesCode;

        @Schema(description = "货架名称")
        private String shelvesName;

        private String containerCode;

    }


    // ---------- 库存状态 ----------

    @Data
    public static class StoragePackage {

        @Schema(description = "包装材料")
        private String wrapSpec;

        @Schema(description = "包装日期")
        private Date wrapTime;

        @Schema(description = "包装操作员")
        private String wrapWorkerName;

    }

    @Data
    public static class StorageStatus {

        @Schema(description = "入库时间")
        private LocalDateTime warehouseInTime;

        @Schema(description = "当前库存")
        private Double warehouseNum;

        @Schema(description = "失效日期")
        private LocalDate lapseTime;

        @Schema(description = "状态")
        private Integer status;

    }


}
