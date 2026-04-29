package com.kitex.mom.plan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 订单生命周期-计划VO
 *
 * @author kitex
 * @date 2025-05-12 09:17:34
 */
@Data
@Schema(description = "订单生命周期-计划VO")
public class OmsLifePlanVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 排产号
     */
    @Schema(description = "排产号")
    private String no;

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
    private String specificationModel;

    /**
     * 物料属性
     */
    @Schema(description = "产品属性")
    private String materialAttribute;

    /**
     * 数量
     */
    @Schema(description = "数量")
    private BigDecimal num;

    /**
     * 下达时间
     */
    @Schema(description = "下达时间")
    private LocalDateTime issueTime;

    /**
     * 需求时间
     */
    @Schema(description = "需求时间")
    private LocalDate demandTime;

    /**
     * 完成数量
     */
    @Schema(description = "完成数量")
    private BigDecimal finishNum;

    /**
     * 完成时间
     */
    @Schema(description = "完成时间")
    private LocalDate finishTime;

    /**
     * 状态, 0:未下发, 1:已下发, 2: 生产中, 3: 已完成, 4:已关闭
     */
    @Schema(description = "状态, 0:未下发, 1:已下发, 2: 生产中, 3: 已完成, 4:已关闭")
    private String status;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    private String factoryCode;

    /**
     * 类型
     */
    @Schema(description = "类型")
    private String type;
}