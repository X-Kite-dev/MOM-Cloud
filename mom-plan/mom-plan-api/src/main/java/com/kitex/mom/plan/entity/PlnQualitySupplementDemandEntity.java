package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import com.kitex.mom.plan.dto.PlanQualitySupplementProgressDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 质量不可逆损失形成的独立补产需求及下游执行回执。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_quality_supplement_demand")
@EqualsAndHashCode(callSuper = true)
public class PlnQualitySupplementDemandEntity extends BaseEntity<PlnQualitySupplementDemandEntity> {
    private String supplementNo;
    private String submitRequestId;
    private String requestFingerprint;
    private Long caseId;
    private Long sourceIssueId;
    private String sourceIssueNo;
    private String productCode;
    private String productName;
    private BigDecimal sourceRequiredQuantity;
    private BigDecimal coveredQuantitySnapshot;
    private BigDecimal pendingQuantitySnapshot;
    private BigDecimal requestQuantity;
    private String qualityEvidenceFingerprint;
    private LocalDate demandDate;
    private String demandWarehouseCode;
    private String demandWarehouseName;
    private String reason;
    /**
     * SUBMITTED、REJECTED、DEMAND_CREATED、MRP_GENERATED、FULFILLED、CANCELLED、WITHDRAWN、STALE。
     */
    private String status;
    /**
     * null为历史即时MRP模型；2为审批、MPS/MRP分阶段模型。
     */
    private Integer planningModelVersion;
    private String applicantCode;
    private String applicantName;
    private LocalDateTime submittedTime;
    private String reviewRequestId;
    private String reviewReason;
    private String reviewerCode;
    private String reviewerName;
    private LocalDateTime reviewedTime;
    private Long productDemandId;
    private String productDemandNo;
    private Long supplementIssueId;
    private String supplementIssueNo;
    private LocalDateTime mrpGeneratedTime;
    private String cancelRequestId;
    private String cancelReason;
    private String cancelledBy;
    private String cancelledByName;
    private LocalDateTime cancelledTime;
    private LocalDateTime fulfilledTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    @TableField(exist = false)
    private String downstreamStatus;
    @TableField(exist = false)
    private java.util.List<PlanQualitySupplementProgressDTO.RootPlan> productionPlans;
    @TableField(exist = false)
    private String downstreamMessage;
    @TableField(exist = false)
    private Integer mrpLineCount;
    @TableField(exist = false)
    private Integer mrpIssuedLineCount;
    @TableField(exist = false)
    private String supplementIssueStatus;
    @TableField(exist = false)
    private Boolean supplementIssueLocked;
    @TableField(exist = false)
    private BigDecimal completedQuantity;
    @TableField(exist = false)
    private Long withdrawalId;
    @TableField(exist = false)
    private String withdrawalNo;
    @TableField(exist = false)
    private String withdrawalStatus;
    @TableField(exist = false)
    private String withdrawalEvidenceFingerprint;
    @TableField(exist = false)
    private String withdrawalPhaseSummary;
    @TableField(exist = false)
    private String withdrawalLastError;
}
