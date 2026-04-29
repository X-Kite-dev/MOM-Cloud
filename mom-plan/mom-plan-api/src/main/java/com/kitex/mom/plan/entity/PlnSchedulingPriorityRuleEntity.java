package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 排产优先级规则
 */
@Data
@TableName("pln_scheduling_priority_rule")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "排产优先级规则")
public class PlnSchedulingPriorityRuleEntity extends BaseEntity<PlnSchedulingPriorityRuleEntity> {

    @Schema(description = "规则集ID")
    private Long ruleSetId;

    @Schema(description = "优先级模式: WEIGHT/SEQUENCE")
    private String priorityMode;

    @Schema(description = "规则内容JSON")
    private String ruleContent;

    @Schema(description = "是否启用")
    private Boolean enabled;
}
