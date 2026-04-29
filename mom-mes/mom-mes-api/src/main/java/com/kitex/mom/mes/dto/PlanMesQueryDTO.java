package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * T+N计划查询DTO
 *
 * @author kitex
 * @date 2025-02-27 16:52:41
 */
@Data
@Schema(description = "T+N计划查询DTO")
public class PlanMesQueryDTO {

    /**
     * 物料编码列表
     */
    @Schema(description = "物料编码列表")
    private List<String> matCodes;

    /**
     * 开始时间
     */
    @Schema(description = "开始时间")
    private LocalDate time;
}