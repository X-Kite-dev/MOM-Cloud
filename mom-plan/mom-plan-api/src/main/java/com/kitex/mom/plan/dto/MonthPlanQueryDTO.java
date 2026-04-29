package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 月度计划查询DTO
 *
 * @author kitex
 * @date 2025-02-10 13:49:21
 */
@Data
@Schema(description = "月度计划查询DTO")
public class MonthPlanQueryDTO {

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 物料编码列表
     */
    @Schema(description = "物料编码列表")
    private List<String> matCodes;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 开始时间
     */
    @Schema(description = "开始时间")
    private LocalDate time;
    /**
     * 客户编码
     */
    @Schema(description = "客户编码")
    private String customerCode;

    /**
     * 需求来源类型（FORECAST/CONFIRMED）
     */
    @Schema(description = "需求来源类型（FORECAST/CONFIRMED）")
    private String sourceType;

    /**
     * 版本号
     */
    @Schema(description = "版本号")
    private String versionNo;

    /**
     * 是否生效版本
     */
    @Schema(description = "是否生效版本")
    private Boolean effectiveFlag;
}