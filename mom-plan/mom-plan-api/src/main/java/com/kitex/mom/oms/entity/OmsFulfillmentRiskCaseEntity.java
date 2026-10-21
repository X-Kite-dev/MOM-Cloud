package com.kitex.mom.oms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** OMS交付行履约风险案件；风险恢复只进入待关闭，须复核权威事实后销案。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("oms_fulfillment_risk_case")
@EqualsAndHashCode(callSuper = true)
public class OmsFulfillmentRiskCaseEntity extends BaseEntity<OmsFulfillmentRiskCaseEntity> {
    private String caseNo;
    private Long orderDetailId;
    private String orderCode;
    private String productCode;
    private String demandNo;
    private String riskCode;
    private String riskName;
    private String severity;
    private String status;
    private Integer occurrenceCount;
    private String sourceFingerprint;
    private String evidenceSnapshot;
    private LocalDateTime firstDetectedTime;
    private LocalDateTime lastDetectedTime;
    private String clearedEvidenceFingerprint;
    private String clearedEvidenceSnapshot;
    private LocalDateTime lastClearedTime;
    private Long ownerUserId;
    private String ownerCode;
    private String ownerName;
    private LocalDateTime responseDueTime;
    private LocalDateTime acknowledgedTime;
    private String actionPlan;
    private String actionResult;
    private String closedBy;
    private String closedByName;
    private LocalDateTime closedTime;
    private String closeRemark;
    private String lastOperationRequestId;
    private String lastOperationType;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    @TableField(exist = false)
    private Boolean overdue;
}
