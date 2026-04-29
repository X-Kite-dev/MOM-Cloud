package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 创建排产规则集入参
 */
@Data
@Schema(description = "创建排产规则集入参")
public class PlnSchedulingRuleSetCreateDTO {

    @Schema(description = "规则编码")
    private String ruleCode;

    @Schema(description = "规则名称")
    private String ruleName;

    @Schema(description = "规则版本")
    private Integer ruleVersion;

    @Schema(description = "是否生效")
    private Boolean active;

    @Schema(description = "是否默认")
    private Boolean defaultRule;

    @Schema(description = "规则配置(JSON)")
    private String ruleContent;

    @Schema(description = "备注")
    private String remark;
}
