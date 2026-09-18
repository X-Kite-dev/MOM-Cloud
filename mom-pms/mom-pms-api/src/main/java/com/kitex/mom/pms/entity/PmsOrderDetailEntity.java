package com.kitex.mom.pms.entity;

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
 * 采购订单表
 *
 * @author kitex
 * @date 2025-01-07 13:36:28
 */
@Data
@TenantTable
@TableName("pms_order_detail")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "pms_order_detail")
public class PmsOrderDetailEntity extends BaseEntity<PmsOrderDetailEntity> {
    /**
     * 采购订单id
     */
    @Schema(description = "采购订单id")
    private Long pmsOrderId;

    /**
     * 采购计划号
     */
    @Schema(description = "采购计划号")
    private String pmsPlanNo;

    /**
     * 订单号
     */
    @Schema(description = "订单号")
    private String orderNo;

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
     * 物料单位
     */
    @Schema(description = "物料单位")
    private String matUnit;
    /**
     * 物料规格
     */
    @Schema(description = "物料规格")
    private String matSpec;
    /**
     * 要货数量
     */
    @Schema(description = "要货数量")
    private Double orderNum;
    /**
     * 要货时间
     */
    @Schema(description = "要货时间")
    private LocalDate deliveryTime;
    /**
     * 到货数量
     */
    @Schema(description = "到货数量")
    private Double arrivalNum;

    /** 审批后取消且不再允许收货的数量；原订购量和已到货量均保留为历史事实。 */
    @Schema(description = "已取消未到货数量")
    private Double cancelledNum;

    @Schema(description = "执行取消的订单变更申请ID")
    private Long cancellationRequestId;

    @Schema(description = "最近一次受控变更申请ID")
    private Long lastChangeRequestId;

    @Schema(description = "订单行受控变更版本")
    private Integer changeVersion;
    /**
     * 单价
     */
    @Schema(description = "单价")
    private BigDecimal price;

    @Schema(description = "订单行冻结的价格库记录")
    private Long priceId;

    @Schema(description = "订单行冻结的供应商物料认证记录")
    private Long materialAuthorizationId;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
