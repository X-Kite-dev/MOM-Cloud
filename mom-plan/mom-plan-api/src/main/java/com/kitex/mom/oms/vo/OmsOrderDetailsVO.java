package com.kitex.mom.oms.vo;

import com.baomidou.mybatisplus.extension.activerecord.Model;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 订单交付信息VO
 *
 * @author kitex
 * @date 2025-01-07 14:38:56
 */
@Data
@Schema(description = "订单交付信息VO")
public class OmsOrderDetailsVO extends Model<OmsOrderDetailsVO> {

    /**
     * 所属订单
     */
    @Schema(description = "所属订单")
    private String orderCode;

    /**
     * 客户编码
     */
    @Schema(description = "客户编码")
    private String customerCode;

    /**
     * 客户名称
     */
    @Schema(description = "客户名称")
    private String customerName;

    /**
     * 产品编码
     */
    @Schema(description = "产品编码")
    private String productCode;

    /**
     * 产品名称
     */
    @Schema(description = "产品名称")
    private String productName;

    /**
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String productSpec;

    /**
     * 产品单位
     */
    @Schema(description = "产品单位")
    private String productUnit;

    /**
     * 交付时间
     */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @Schema(description = "交付时间")
    private LocalDate deliverTime;

    /**
     * 交付数量
     */
    @Schema(description = "交付数量")
    private BigDecimal deliverNum;

    /**
     * 已交付数量
     */
    @Schema(description = "已交付数量")
    private BigDecimal deliveredNum;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;
}