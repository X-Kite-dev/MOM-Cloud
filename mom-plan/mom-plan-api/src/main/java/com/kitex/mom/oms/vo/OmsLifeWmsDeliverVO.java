package com.kitex.mom.oms.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 发货计划表
 *
 * @author kitex
 * @date 2025-01-13 09:19:26
 */
@Data
@Schema(description = "发货计划表")
public class OmsLifeWmsDeliverVO {


    /**
     * 发货计划编码
     */
    @Schema(description = "发货计划编码")
    private String deliverPlanCode;

    /**
     * 订单编码
     */
    @Schema(description = "订单编码")
    private String orderCode;

    /**
     * 订单明细id
     */
    @Schema(description = "订单明细id")
    private Long orderCodeDetails;

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
     * 计划发货时间
     */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @Schema(description = "计划发货时间")
    private LocalDate planDeliverTime;

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
     * 补发数量
     */
    @Schema(description = "补发数量")
    private BigDecimal reissueNum;

    /**
     * 发货状态
     */
    @Schema(description = "发货状态")
    private String deliverState;

    /**
     * 客户仓库
     */
    @Schema(description = "客户仓库")
    private String customerWarehouse;

    /**
     * 客户收货地址
     */
    @Schema(description = "客户收货地址")
    private String customerAddress;

    /**
     * 客户联系人
     */
    @Schema(description = "客户联系人")
    private String customerContact;

    /**
     * 客户联系电话
     */
    @Schema(description = "客户联系电话")
    private String customerPhone;

    /**
     * 创建人姓名
     */
    @Schema(description = "创建人姓名")
    private String createByName;

    /**
     * 更新人姓名
     */
    @Schema(description = "更新人姓名")
    private String updateByName;


    /**
     * 发货单号
     */
    @TableField(exist = false)
    @Schema(description = "发货单号")
    private String deliverCode;

}