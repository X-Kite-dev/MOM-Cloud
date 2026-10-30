package com.kitex.mom.mes.entity;

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

/**
 * MES 已提交执行事实向 PLAN/ERP 转交的本地事务 Outbox。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_erp_posting_outbox")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "MES执行事实ERP转交Outbox")
public class MesErpPostingOutboxEntity extends BaseEntity<MesErpPostingOutboxEntity> {
    private String requestId;
    private String requestFingerprint;
    private String eventType;
    private String sourceDocumentNo;
    private String sourceDocumentVersion;
    private String formalPlanContractNo;
    private Integer formalPlanContractVersion;
    private String formalPlanContractFingerprint;
    private Long planningPolicyId;
    private Integer planningPolicyVersion;
    private String planningPolicyFingerprint;
    @JsonIgnore
    private String payloadJson;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextRetryTime;
    private String leaseToken;
    private LocalDateTime leaseUntil;
    private Long planOutboxId;
    private String planStatus;
    private String lastError;
    private LocalDateTime queuedTime;
    private LocalDateTime transferredTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
