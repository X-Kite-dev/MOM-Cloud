package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TenantTable
@TableName("mdm_duration_calibration_proposal")
@EqualsAndHashCode(callSuper = true)
public class MdmDurationCalibrationProposalEntity extends BaseEntity<MdmDurationCalibrationProposalEntity> {
    private String proposalNo;
    private Integer versionNo;
    private Long runId;
    private Long policyId;
    private String targetType;
    private String scopeType;
    private String resourceCode;
    private String resourceName;
    private String processCode;
    private String processName;
    private String fromToolingCode;
    private String toToolingCode;
    private String fromFamilyCode;
    private String fromFamilyName;
    private String toFamilyCode;
    private String toFamilyName;
    private Integer sampleCount;
    private Integer excludedSampleCount;
    private Long currentFixedSeconds;
    private BigDecimal currentUnitSeconds;
    private Long proposedFixedSeconds;
    private BigDecimal proposedUnitSeconds;
    private Long currentChangeoverSeconds;
    private Long proposedChangeoverSeconds;
    private BigDecimal cleaningRatioPct;
    private BigDecimal meanSeconds;
    private BigDecimal medianSeconds;
    private BigDecimal p90Seconds;
    private BigDecimal coefficientVariationPct;
    private BigDecimal rSquared;
    private BigDecimal deviationPct;
    private String confidenceLevel;
    private Boolean publishEligible;
    private String eligibilityReason;
    private String evidenceFingerprint;
    private String modelSnapshot;
    @Schema(description = "DRAFT/SUBMITTED/ACTIVE/REJECTED/RETIRED")
    private String status;
    private LocalDateTime effectiveTime;
    private String submittedBy;
    private String submittedByName;
    private LocalDateTime submittedTime;
    private String approvedBy;
    private String approvedByName;
    private LocalDateTime approvedTime;
    private String rejectedBy;
    private String rejectedByName;
    private LocalDateTime rejectedTime;
    private String reviewComment;
    private String closedBy;
    private String closedByName;
    private LocalDateTime closedTime;
    private String closeReason;
    private Long publishedRuleId;
    private Long supersededByProposalId;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
