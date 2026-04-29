package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 仓库盘点计划查询DTO
 *
 * @author kitex
 * @date 2024-12-31 11:18:29
 */
@Data
@Schema(description = "仓库盘点计划查询DTO")
public class WmsInsideInventoryPlanQueryDTO {

    /**
     * 盘点编号
     */
    @Schema(description = "盘点编号")
    private String planNo;

    /**
     * 仓库编号
     */
    @Schema(description = "仓库编号")
    private String warehouseCode;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 状态：0正在执行中/1 已结束
     */
    @Schema(description = "状态：0正在执行中/1 已结束")
    private String status;

    /**
     * 盘点损益处理状态：0 待处理/ 1已处理
     */
    @Schema(description = "盘点损益处理状态：0 待处理/ 1已处理")
    private String handleState;
}