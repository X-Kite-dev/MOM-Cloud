package com.kitex.mom.oms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import com.kitex.mom.oms.dto.HistoryDeliverOrderDto;
import com.kitex.mom.oms.dto.OmsOrderMesInfoDto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 订单评审表
 *
 * @author kitex
 * @date 2025-01-07 14:32:02
 */
@Data
@TenantTable
@TableName("oms_order_base_review")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "订单评审表")
public class OmsOrderBaseReviewEntity extends BaseEntity<OmsOrderBaseReviewEntity> {


    /**
     * 订单号
     */
    @Schema(description = "订单号")
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
     * 客户零件号
     */
    @Schema(description = "客户零件号")
    private String customerPartCode;

    /**
     * 订单类型
     */
    @Schema(description = "订单类型")
    private String orderType;

    /**
     * 订单状态 1 待评审 2评审完成
     */
    @Schema(description = "订单状态 1 待评审 2评审完成")
    private String orderState;

    /**
     * 是否变更订单 0 否 1是
     */
    @Schema(description = "是否变更订单 0 否 1是")
    private String changeType;

    /**
     * 变更信息
     */
    @Schema(description = "变更信息")
    private String changeMsg;

    /**
     * remark
     */
    @Schema(description = "评审意见")
    private String remark;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 生产计划信息(已下发)
     */
    @Schema(description = "生产计划信息(已下发)")
    @TableField(exist = false)
    private OmsOrderMesInfoDto mesInfo;

    /**
     * 交付评审信息详情列表
     */
    @Schema(description = "交付评审信息详情列表")
    @TableField(exist = false)
    private List<OmsOrderDetailsReviewEntity> orderDetailsList;

    /**
     * 历史未交订单
     */
    @Schema(description = "历史未交订单")
    @TableField(exist = false)
    private List<HistoryDeliverOrderDto> historyDeliverOrderDtoList;

}