package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * T+N计划查询DTO
 *
 * @author kitex
 * @date 2025-02-05 15:14:43
 */
@Data
@Schema(description = "T+N计划查询DTO")
public class TemporaryPlanQueryDTO {

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
     * 排产天数
     */
    @Schema(description = "排产天数")
    private Integer planNum;

    /**
     * 客户编码
     */
    @Schema(description = "客户编码")
    private String customerCode;
}