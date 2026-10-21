package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 设备、工装和人员异常局部重排的版本化治理策略。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_resource_replan_policy")
@EqualsAndHashCode(callSuper = true)
public class PlnResourceReplanPolicyEntity extends BaseEntity<PlnResourceReplanPolicyEntity> {
    private String policyCode;
    private String policyName;
    /** ALL、DEVICE、TOOLING、EMPLOYEE。 */
    private String resourceType;
    /** 空值表示该资源类型下通配。 */
    private String resourceCode;
    private Integer freezeMinutes;
    private Integer maxDelayMinutes;
    private Integer priority;
    private Integer policyVersion;
    private String policyFingerprint;
    /** DRAFT、ACTIVE、RETIRED。 */
    private String status;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private String authorCode;
    private String authorName;
    private String activationRequestId;
    private String approverCode;
    private String approverName;
    private LocalDateTime approvedTime;
    private String retireRequestId;
    private String retireReason;
    private String retiredBy;
    private LocalDateTime retiredTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
