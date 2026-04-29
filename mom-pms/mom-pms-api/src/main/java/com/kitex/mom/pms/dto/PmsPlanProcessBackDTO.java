package com.kitex.mom.pms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

/**
 * 采购计划审批回调
 *
 * @author kitex
 * @since 2025-2-10 16:17:27
 */
@Data
@Schema(description = "采购计划审批回调")
public class PmsPlanProcessBackDTO {

    /**
     * 是否通过
     */
    @Schema(description = "是否通过")
    private Boolean approveRes;

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
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 物料单位
     */
    @Schema(description = "物料单位")
    private String matUnit;

    /**
     * 物料规格
     */
    @Schema(description = "物料规格")
    private String matSpec;

    /**
     * 要货时间
     */
    @Schema(description = "要货时间")
    private LocalDate planDeliveryTime;

    /**
     * 采购数量
     */
    @Schema(description = "采购数量")
    private Double pmsNum;

}