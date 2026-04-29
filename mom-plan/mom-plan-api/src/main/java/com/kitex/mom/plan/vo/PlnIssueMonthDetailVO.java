package com.kitex.mom.plan.vo;

import com.kitex.mom.plan.entity.PlnIssueMonthEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 月计划下达详情VO
 *
 * @author kitex
 * @date 2025-02-11 13:52:18
 */
@Data
@Schema(description = "月计划下达详情VO")
public class PlnIssueMonthDetailVO extends PlnIssueMonthEntity {

    /**
     * 所属组织ID
     */
    @Schema(description = "所属组织ID")
    private List<Map<Object, Object>> detailDays;
}