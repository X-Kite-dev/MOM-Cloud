package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

/**
 * 原材料信息
 *
 * @author kitex
 * @since 2025-04-24
 */
@Data
public class MatRetrospectDTO {
    private String matCode;
    @Schema(description = "FEED_HISTORY投料历史或BACKFLUSH_HISTORY倒冲历史；不是净消耗证明")
    private String sourceType;

    // ------基本信息-------

    @Schema(description = "生产工单号")
    private String taskCode;

    @Schema(description = "物料名称")
    private String matName;

    @Schema(description = "批次号")
    private String batchNo;

    @Schema(description = "供应商编码")
    private String supplierCode;

    @Schema(description = "供应商名称")
    private String supplierName;

    @Schema(description = "到货日期")
    private LocalDate matProduceDate;

    @Schema(description = "有效期")
    private LocalDate matValidEndDate;

    @Schema(description = "采购订单号")
    private String planOrder;

    // ------存储条件-------

    @Schema(description = "温度")
    private String temperature;

    @Schema(description = "湿度")
    private String humidity;

    @Schema(description = "存储位置")
    private String location;

    // ------供应商链信息-------

    @Schema(description = "供应商资质")
    private String aptitude;

    @Schema(description = "次级供应商")
    private String secondSupplierName;


}
