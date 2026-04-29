package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

/**
 * 计划下达DTO
 *
 * @author kitex
 * @date 2025-02-13 09:49:46
 */
@Data
@Schema(description = "计划下达DTO")
public class PlnIssueDTO {

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
     * 下达时间
     */
    @Schema(description = "下达时间")
    private LocalDate[] issueTime;

    /**
     * 需求时间
     */
    @Schema(description = "需求时间")
    private LocalDate[] demandTime;

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
     * 状态, 0:未下发, 1:已下发, 2:已关闭
     */
    @Schema(description = "状态, 0:未下发, 1:已下发, 2:已关闭")
    private String status;
}