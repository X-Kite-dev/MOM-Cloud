package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 排产规则集
 */
@Data
@TenantTable
@TableName("pln_scheduling_rule_set")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "排产规则集")
public class PlnSchedulingRuleSetEntity extends BaseEntity<PlnSchedulingRuleSetEntity> {

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

    @Schema(description = "规则配置")
    private String ruleContent;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
