package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 查询库存汇总数据
 *
 * @author kitex
 * @date 2025-1-7 18:20:55
 */
@Data
@Schema(description = "查询库存汇总数据")
public class WmsLiveStockQuerySumDTO {

    /**
     * 仓库编码
     */
    @NotBlank
    @Schema(description = "仓库编码")
    private String warehouseCode;

    /**
     * 库位编码
     */
    @NotBlank
    @Schema(description = "库位编码")
    private String siteCode;

    /**
     * 物料编码
     */
    @NotBlank
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 物料批次
     */
    @Schema(description = "物料批次")
    private String matBatch;

    /**
     * 供应商编码
     */
    @Schema(description = "供应商编码")
    private String supplierCode;

    /**
     * 容器编码
     */
    @Schema(description = "容器编码")
    private String containerCode;

}