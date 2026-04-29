package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 排产重排策略
 */
@Data
@TableName("pln_scheduling_replan_rule")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "排产重排策略")
public class PlnSchedulingReplanRuleEntity extends BaseEntity<PlnSchedulingReplanRuleEntity> {

    @Schema(description = "规则集ID")
    private Long ruleSetId;

    @Schema(description = "计划状态")
    private String issueStatus;

    @Schema(description = "重排策略: ALLOW/WARN/FORBID")
    private String policy;

    @Schema(description = "是否需二次确认")
    private Boolean requireConfirm;
}
