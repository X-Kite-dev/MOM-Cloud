package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 质量偏差触发排程处置的版本化策略。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_quality_replan_policy")
@EqualsAndHashCode(callSuper = true)
public class PlnQualityReplanPolicyEntity extends BaseEntity<PlnQualityReplanPolicyEntity> {
    private String policyCode;
    private String policyName;
    /**
     * 为空表示工厂通用策略，产品专属策略优先。
     */
    private String productCode;
    /**
     * ANY：任一阈值命中；ALL：所有已配置阈值均命中。
     */
    private String thresholdMode;
    private BigDecimal minAtRiskQuantity;
    /**
     * 0~100百分比。
     */
    private BigDecimal minDeviationRate;
    private BigDecimal minScrapQuantity;
    private Integer freezeMinutes;
    private Integer maxDelayMinutes;
    private Integer priority;
    private Integer policyVersion;
    /**
     * DRAFT、ACTIVE、RETIRED。
     */
    private String status;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private String policyFingerprint;
    private String activationRequestId;
    private String authorCode;
    private String authorName;
    private String approverCode;
    private String approverName;
    private LocalDateTime approvedTime;
    private String retireReason;
    private String retireRequestId;
    private String retiredBy;
    private LocalDateTime retiredTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
