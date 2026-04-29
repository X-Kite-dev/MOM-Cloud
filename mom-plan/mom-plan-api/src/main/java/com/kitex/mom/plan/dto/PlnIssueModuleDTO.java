package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 生产计划下发DTO
 *
 * @author kitex
 * @date 2025-03-21 16:54:56
 */
@Data
@Schema(description = "生产计划下发DTO")
public class PlnIssueModuleDTO {

    /**
     * 生产排产结果ids
     */
    @Schema(description = "生产排产结果ids")
    private Long[] ids;

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
}