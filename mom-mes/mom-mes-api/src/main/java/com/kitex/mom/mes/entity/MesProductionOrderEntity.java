package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * 生产订单主表 实体类
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "生产订单主表")
@TableName("mes_production_order")
public class MesProductionOrderEntity extends BaseEntity<MesProductionOrderEntity> {

    /**
     * 生产订单编号
     */
    @Schema(description = "生产订单编号")
    private String orderNo;

    /**
     * 订单类型：STANDARD-标准订单，URGENT-紧急订单，TRIAL-试制订单
     */
    @Schema(description = "订单类型：STANDARD-标准订单，URGENT-紧急订单，TRIAL-试制订单")
    private String orderType;

    /**
     * 来源类型：MANUAL-手工创建，PLAN-计划生成，SALE-销售订单
     */
    @Schema(description = "来源类型：MANUAL-手工创建，PLAN-计划生成，SALE-销售订单")
    private String sourceType;

    /**
     * 来源单号
     */
    @Schema(description = "来源单号")
    private String sourceNo;

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
     * 产品规格型号
     */
    @Schema(description = "产品规格型号")
    private String productSpec;

    /**
     * 单位
     */
    @Schema(description = "单位")
    private String unit;

    /**
     * 订单数量
     */
    @Schema(description = "订单数量")
    private BigDecimal orderQuantity;

    /**
     * 完成数量
     */
    @Schema(description = "完成数量")
    private BigDecimal finishedQuantity;

    /**
     * 剩余数量
     */
    @Schema(description = "剩余数量")
    private BigDecimal remainingQuantity;

    /**
     * BOM ID
     */
    @Schema(description = "BOM ID")
    private String bomId;

    /**
     * BOM版本
     */
    @Schema(description = "BOM版本")
    private String bomVersion;

    /**
     * 优先级（1-10，数字越小优先级越高）
     */
    @Schema(description = "优先级（1-10，数字越小优先级越高）")
    private Integer priority;

    /**
     * 计划开始日期
     */
    @Schema(description = "计划开始日期")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planStartDate;

    /**
     * 计划结束日期
     */
    @Schema(description = "计划结束日期")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planEndDate;

    /**
     * 实际开始日期
     */
    @Schema(description = "实际开始日期")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime actualStartDate;

    /**
     * 实际结束日期
     */
    @Schema(description = "实际结束日期")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime actualEndDate;

    /**
     * 状态：NEW-新建，SCHEDULED-已排程，RELEASED-已下达，IN_PRODUCTION-生产中，COMPLETED-完成，CANCELLED-取消
     */
    @Schema(description = "状态：NEW-新建，SCHEDULED-已排程，RELEASED-已下达，IN_PRODUCTION-生产中，COMPLETED-完成，CANCELLED-取消")
    private String status;

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
     * 交货日期
     */
    @Schema(description = "交货日期")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime deliveryDate;

    /**
     * 销售订单号
     */
    @Schema(description = "销售订单号")
    private String salesOrderNo;

    /**
     * 车间编码
     */
    @Schema(description = "车间编码")
    private String workshopCode;

    /**
     * 车间名称
     */
    @Schema(description = "车间名称")
    private String workshopName;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}