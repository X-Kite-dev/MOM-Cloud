package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 发货单明细基础表
 *
 * @author kitex
 * @date 2025-01-13 09:35:13
 */
@Data
@TableName("wms_deliver_goods_details")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "发货单明细基础表")
public class WmsDeliverGoodsDetailsEntity extends Model<WmsDeliverGoodsDetailsEntity> {


    /**
     * 记录ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "记录ID")
    private Long id;

    /**
     * 发货单号
     */
    @Schema(description = "发货单号")
    private String deliverCode;

    /**
     * 订单号
     */
    @Schema(description = "订单号")
    private String orderCode;

    /**
     * 发货计划号
     */
    @Schema(description = "发货计划号")
    private String deliverPlanCode;

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
     * 单位
     */
    @Schema(description = "单位")
    private String productUnit;

    /**
     * 发货数量
     */
    @Schema(description = "发货数量")
    private BigDecimal deliverNum;

    /**
     * 已发货数量
     */
    @Schema(description = "已发货数量")
    private BigDecimal deliveredNum;

    /**
     * 退货数量
     */
    @Schema(description = "退货数量")
    private BigDecimal returnNum;

    /**
     * 状态明细
     */
    @Schema(description = "状态明细")
    private String deliverState;


    @TableField(exist = false)
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;

}