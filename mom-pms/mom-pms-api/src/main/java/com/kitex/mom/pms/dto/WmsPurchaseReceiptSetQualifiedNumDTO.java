package com.kitex.mom.pms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 采购收料单明细新增DTO
 *
 * @author kitex
 * @date 2025-1-23 15:15:27
 */
@Data
@Schema(description = "采购收料单明细新增DTO")
public class WmsPurchaseReceiptSetQualifiedNumDTO {

    /**
     * 采购收料单号
     */
    @Schema(description = "采购收料单号")
    private String billNo;

    /**
     * 质检任务ID
     */
    @Schema(description = "质检任务ID")
    private Long qualityId;

    @Schema(description = "物料编码")
    private String matCode;

    @Schema(description = "物料名称")
    private String matName;

    @Schema(description = "物料批次")
    private String matBatch;

    /**
     * 收料数量
     */
    @Schema(description = "收料数量")
    private Double matNum;

    /**
     * 判定数量
     */
    @Schema(description = "判定数量")
    private Double qualityNum;

    /**
     * 质检结果
     */
    @Schema(description = "质检结果")
    private String result;

}