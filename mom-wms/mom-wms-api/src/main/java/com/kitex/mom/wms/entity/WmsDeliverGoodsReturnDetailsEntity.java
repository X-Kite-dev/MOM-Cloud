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
 * 发货退货表
 *
 * @author kitex
 * @date 2025-01-15 11:50:18
 */
@Data
@TableName("wms_deliver_goods_return_details")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "发货退货表-明细")
public class WmsDeliverGoodsReturnDetailsEntity extends Model<WmsDeliverGoodsReturnDetailsEntity> {


    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "id")
    private Long id;

    /**
     * baseDetailsId 发货单明细id
     */
    @Schema(description = "baseDetailsId")
    private Long baseDetailsId;

    /**
     * 退货单主表id
     */
    @Schema(description = "returnId")
    private Long returnId;

    /**
     * 订单号
     */
    @Schema(description = "订单号")
    private String orderCode;

    /**
     * 发货计划单号
     */
    @Schema(description = "发货计划单号")
    private String deliverPlanCode;

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
     * 退货数量
     */
    @Schema(description = "退货数量")
    private BigDecimal returnNum;


    /**
     * 可退货数量
     */
    @TableField(exist = false)
    @Schema(description = "可退货数量")
    private BigDecimal unReturnNum;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;
}