package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 调拨单查询DTO
 *
 * @author kitex
 * @date 2024-12-31 11:12:56
 */
@Data
@Schema(description = "调拨单查询DTO")
public class WmsAllocationBillQueryDTO {

    /**
     * 调拨单号
     */
    @Schema(description = "调拨单号")
    private String billNo;

    /**
     * 旧仓库编码
     */
    @Schema(description = "旧仓库编码")
    private String oldWarehouseCode;

    /**
     * 旧仓库名称
     */
    @Schema(description = "旧仓库名称")
    private String oldWarehouseName;

    /**
     * 新仓库编码
     */
    @Schema(description = "新仓库编码")
    private String newWarehouseCode;

    /**
     * 新仓库名称
     */
    @Schema(description = "新仓库名称")
    private String newWarehouseName;

    /**
     * 调拨完成状态, 0未完成 1完成
     */
    @Schema(description = "调拨完成状态, 0未完成 1完成")
    private String allocationState;
}