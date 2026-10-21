package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** ERP 正式计划结构化收件箱；保存规范载荷而不是连接器密钥或未经治理的任意原文。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_erp_plan_inbox")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "ERP正式计划收件箱")
public class PlnErpPlanInboxEntity extends BaseEntity<PlnErpPlanInboxEntity> {
    private String requestId;
    private String requestFingerprint;
    private String sourceSystemCode;
    private String sourceFactoryCode;
    private String sourcePlanNo;
    private String sourcePlanVersion;
    private String sourceContentFingerprint;
    private String normalizedPayloadJson;
    private String status;
    private String rejectionReason;
    private String contractNo;
    private Integer contractVersion;
    private Long planningPolicyId;
    private Integer planningPolicyVersion;
    private String planningPolicyFingerprint;
    private LocalDateTime verifiedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
