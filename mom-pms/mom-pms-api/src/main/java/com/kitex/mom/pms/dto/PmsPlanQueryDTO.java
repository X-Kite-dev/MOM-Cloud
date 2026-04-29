package com.kitex.mom.pms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

/**
 * 采购计划查询DTO
 *
 * @author kitex
 * @date 2025-03-04 14:09:27
 */
@Data
@Schema(description = "采购计划查询DTO")
public class PmsPlanQueryDTO {

    /**
     * 采购计划号
     */
    @Schema(description = "采购计划号")
    private String pmsPlanNo;

    /**
     * 采购计划下达号
     */
    @Schema(description = "采购计划下达号")
    private String orderNo;

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
     * 物料规格
     */
    @Schema(description = "物料规格")
    private String matSpec;

    /**
     * 状态（0 未审核 1 审核中 2 审核通过 3 已下发 4 完成 5 驳回）
     */
    @Schema(description = "状态（0 未审核 1 审核中 2 审核通过 3 已下发 4 完成 5 驳回）")
    private String status;

    /**
     * 要货时间
     */
    @Schema(description = "要货时间")
    private LocalDate[] planDeliveryTime;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;
}