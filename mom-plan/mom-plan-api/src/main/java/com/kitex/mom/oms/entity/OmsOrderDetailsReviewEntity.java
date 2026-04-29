package com.kitex.mom.oms.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 订单交付详情评审表
 *
 * @author kitex
 * @date 2025-01-07 14:40:49
 */
@Data
@TableName("oms_order_details_review")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "订单交付详情评审表")
public class OmsOrderDetailsReviewEntity extends Model<OmsOrderDetailsReviewEntity> {


    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "id")
    private Long id;

    /**
     * 所属订单
     */
    @Schema(description = "所属订单")
    private String orderCode;

    /**
     * 订单评审单号
     */
    @Schema(description = "订单评审单号")
    private String orderReviewCode;

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

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}