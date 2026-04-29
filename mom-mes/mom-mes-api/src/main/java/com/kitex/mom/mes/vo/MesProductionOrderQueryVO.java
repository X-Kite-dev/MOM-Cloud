package com.kitex.mom.mes.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * <p>
 * 生产订单查询 VO类
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@Data
@Schema(description = "生产订单查询VO")
public class MesProductionOrderQueryVO {

    /**
     * 生产订单编号
     */
    @Schema(description = "生产订单编号")
    private String orderNo;

    /**
     * 订单类型
     */
    @Schema(description = "订单类型")
    private String orderType;

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
     * 状态
     */
    @Schema(description = "状态")
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
     * 优先级
     */
    @Schema(description = "优先级")
    private Integer priority;

    /**
     * 计划开始日期开始
     */
    @Schema(description = "计划开始日期开始")
    private String planStartDateStart;

    /**
     * 计划开始日期结束
     */
    @Schema(description = "计划开始日期结束")
    private String planStartDateEnd;

    /**
     * 计划结束日期开始
     */
    @Schema(description = "计划结束日期开始")
    private String planEndDateStart;

    /**
     * 计划结束日期结束
     */
    @Schema(description = "计划结束日期结束")
    private String planEndDateEnd;

    /**
     * 交货日期开始
     */
    @Schema(description = "交货日期开始")
    private String deliveryDateStart;

    /**
     * 交货日期结束
     */
    @Schema(description = "交货日期结束")
    private String deliveryDateEnd;

    /**
     * 创建时间开始
     */
    @Schema(description = "创建时间开始")
    private String createTimeStart;

    /**
     * 创建时间结束
     */
    @Schema(description = "创建时间结束")
    private String createTimeEnd;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    private String factoryCode;
}