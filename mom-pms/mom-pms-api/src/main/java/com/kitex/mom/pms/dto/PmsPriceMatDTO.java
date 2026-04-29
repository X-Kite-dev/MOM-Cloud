package com.kitex.mom.pms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 价格-物料DTO
 *
 * @author kitex
 * @date 2025-03-24 17:58:49
 */
@Data
@Schema(description = "价格-物料DTO")
public class PmsPriceMatDTO {

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
     * 单位
     */
    @Schema(description = "单位")
    private String matUnit;

    /**
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 不含税单价
     */
    @Schema(description = "不含税单价")
    private BigDecimal unitPriceWithoutTax;

    /**
     * 税率值
     */
    @Schema(description = "税率值")
    private BigDecimal taxRate;

    /**
     * 含税单价
     */
    @Schema(description = "含税单价")
    private BigDecimal unitPriceWithTax;

    /**
     * 生效时间
     */
    @Schema(description = "生效时间")
    private LocalDateTime effectiveTime;

    /**
     * 失效时间
     */
    @Schema(description = "失效时间")
    private LocalDateTime expirationTime;

    /**
     * 价格来源
     */
    @Schema(description = "价格来源")
    private String priceSource;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 状态
     */
    @Schema(description = "状态")
    private Boolean status;
}