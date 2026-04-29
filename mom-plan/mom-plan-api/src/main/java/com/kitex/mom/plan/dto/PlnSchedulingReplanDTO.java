package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 排产重排参数
 */
@Data
@Schema(description = "排产重排参数")
public class PlnSchedulingReplanDTO {

    @Schema(description = "计划下达ID")
    private Long issueId;

    @Schema(description = "重排目标ID列表")
    private List<Long> targetIds;
}
