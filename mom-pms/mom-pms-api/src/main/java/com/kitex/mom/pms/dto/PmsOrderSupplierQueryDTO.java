package com.kitex.mom.pms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

/**
 * 采购计划-供应商供货物料查询DTO
 *
 * @author kitex
 * @date 2025-04-01 09:11:25
 */
@Data
@Schema(description = "采购计划-供应商供货物料查询DTO")
public class PmsOrderSupplierQueryDTO {

    /**
     * 采购计划号
     */
    @Schema(description = "采购计划号")
    private String pmsPlanNo;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 物料编码列表
     */
    @Schema(description = "物料编码列表")
    private String[] matCodes;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 要货时间
     */
    @Schema(description = "要货时间")
    private LocalDate[] planDeliveryTime;
}