package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 修改日志表
 *
 * @author kitex
 * @date 2025-02-26 10:06:07
 */
@Data
@TableName("pln_update_log")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "修改日志表")
public class PlnUpdateLogEntity extends BaseEntity<PlnUpdateLogEntity> {

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
     * 产品属性
     */
    @Schema(description = "产品属性")
    private String materialAttribute;

    /**
     * 操作的日期
     */
    @Schema(description = "操作的日期")
    private LocalDateTime operateTime;

    /**
     * 所属模块
     */
    @Schema(description = "所属模块")
    private String module;

    /**
     * 计划类型
     */
    @Schema(description = "计划类型")
    private String planType;

    /**
     * 修改类型
     */
    @Schema(description = "修改类型")
    private String updateType;

    /**
     * 新值
     */
    @Schema(description = "新值")
    private String newValue;

    /**
     * 旧值
     */
    @Schema(description = "旧值")
    private String oldValue;
}