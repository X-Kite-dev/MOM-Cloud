package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** ERP 计划与执行事实可靠投递账。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_erp_integration_outbox")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "ERP计划与执行事实可靠投递账")
public class PlnErpIntegrationOutboxEntity extends BaseEntity<PlnErpIntegrationOutboxEntity> {
    private String requestId;
    private String requestFingerprint;
    private String eventType;
    private String sourceModule;
    private String sourceDocumentNo;
    private String sourceDocumentVersion;
    private String erpSystemCode;
    private Long planningPolicyId;
    private Integer planningPolicyVersion;
    private String planningPolicyFingerprint;
    private String formalPlanContractNo;
    private Integer formalPlanContractVersion;
    private String formalPlanContractFingerprint;
    @JsonIgnore
    private String payloadJson;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextRetryTime;
    private String leaseToken;
    private LocalDateTime leaseUntil;
    private String receiptCode;
    @JsonIgnore
    private String receiptPayload;
    private String receiptFingerprint;
    private String lastError;
    private String deadLetterReason;
    private LocalDateTime queuedTime;
    private LocalDateTime sentTime;
    private LocalDateTime completedTime;
    private String manualOperator;
    private String manualReason;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
