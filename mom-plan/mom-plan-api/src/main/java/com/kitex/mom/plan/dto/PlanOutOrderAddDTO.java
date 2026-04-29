package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 外协订单新增DTO
 *
 * @author kitex
 * @date 2025-04-16 15:23:15
 */
@Data
@Schema(description = "外协订单新增DTO")
public class PlanOutOrderAddDTO {

    /**
     * 外协任务ids
     */
    @Schema(description = "外协任务ids")
    private List<Long> outTaskIds;

    /**
     * 外协时间
     */
    @Schema(description = "外协时间")
    private LocalDate outDate;

    /**
     * 需求时间
     */
    @Schema(description = "需求时间")
    private LocalDate orderDate;
}