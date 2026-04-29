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
 * 订单交付信息表
 *
 * @author kitex
 * @date 2025-01-07 14:38:56
 */
@Data
@TableName("oms_order_details")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "订单交付信息表")
public class OmsOrderDetailsEntity extends Model<OmsOrderDetailsEntity> {


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
     * 状态
     */
    @Schema(description = "状态")
    private String state;

    /**
     * 是否变更中 0否  1是
     */
    @Schema(description = "是否变更中")
    private String changeSign;

    @TableField(exist = false)
    private BigDecimal operateNum;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}