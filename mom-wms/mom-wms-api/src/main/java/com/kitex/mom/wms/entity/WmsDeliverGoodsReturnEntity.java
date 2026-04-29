package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

/**
 * 发货退货表
 *
 * @author kitex
 * @date 2025-01-15 11:50:18
 */
@Data
@TenantTable
@TableName("wms_deliver_goods_return")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "发货退货表")
public class WmsDeliverGoodsReturnEntity extends BaseEntity<WmsDeliverGoodsReturnEntity> {

    /**
     * 订单号
     */
    @Schema(description = "订单号")
    private String orderCode;

    /**
     * 发货单号
     */
    @Schema(description = "发货单号")
    private String deliverCode;

    /**
     * 发货计划单号
     */
    @Schema(description = "发货计划单号")
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
     * 退货数量
     */
    @Schema(description = "退货数量")
    private BigDecimal returnNum;

    /**
     * 销售处理人账号
     */
    @Schema(description = "销售处理人账号")
    private String handlePerson;

    /**
     * 销售处理人名称
     */
    @Schema(description = "销售处理人名称")
    private String handlePersonName;

    /**
     * 退货仓库
     */
    @Schema(description = "退货仓库")
    private String returnWarehouse;

    /**
     * 退货仓库名称
     */
    @Schema(description = "退货仓库名称")
    private String returnWarehouseName;

    /**
     * 退货原因
     */
    @Schema(description = "退货原因")
    private String returnReason;

    /**
     * 状态
     */
    @Schema(description = "状态")
    private String state;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 明细
     */
    @TableField(exist = false)
    @Schema(description = "明细")
    private List<WmsDeliverGoodsReturnDetailsEntity> detailsList;


    @TableField(exist = false)
    @Schema(description = "关联通知单")
    private List<WmsInNoticeDetailEntity> inNoticeDetailList;


}