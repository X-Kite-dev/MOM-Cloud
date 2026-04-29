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
import java.time.LocalDate;

/**
 * 发货异常记录表
 *
 * @author kitex
 * @date 2025-01-13 09:26:52
 */
@Data
@TenantTable
@TableName("wms_deliver_goods_abnormal_record")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "发货异常记录表")
public class WmsDeliverGoodsAbnormalRecordEntity extends BaseEntity<WmsDeliverGoodsAbnormalRecordEntity> {


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
     * 计划发货时间
     */
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
     * 异常原因
     */
    @Schema(description = "异常原因")
    private String abnormalReason;

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
     * 填报原因
     */
    @Schema(description = "填报原因")
    private String submitReason;

    /**
     * 状态（0新建 1已填报）
     */
    @Schema(description = "状态")
    private String state;

    /**
     * 创建人名称
     */
    @Schema(description = "创建人名称")
    private String createByName;

    /**
     * 更新人名称
     */
    @Schema(description = "更新人名称")
    private String updateByName;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}