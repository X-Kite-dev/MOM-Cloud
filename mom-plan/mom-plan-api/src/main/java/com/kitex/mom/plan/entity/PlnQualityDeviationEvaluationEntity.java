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

/** 每一笔MES质量数量回执对应一次不可变阈值评估。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_quality_deviation_evaluation")
@EqualsAndHashCode(callSuper = true)
public class PlnQualityDeviationEvaluationEntity extends BaseEntity<PlnQualityDeviationEvaluationEntity> {
    private String evaluationRequestId;
    private Long sourceReceiptId;
    private String sourceRequestId;
    private Long issueId;
    private String issueNo;
    private String productCode;
    private Long policyId;
    private String policyCode;
    private Integer policyVersion;
    private String policyFingerprint;
    private String formulaVersion;
    private BigDecimal plannedQuantity;
    private BigDecimal unqualifiedQuantity;
    private BigDecimal qualityIsolatedQuantity;
    private BigDecimal qualityReleasedQuantity;
    private BigDecimal qualityRejectedQuantity;
    private BigDecimal scrapQuantity;
    private BigDecimal reworkQuantity;
    private BigDecimal reworkCompletedQuantity;
    private BigDecimal reworkQualifiedQuantity;
    private BigDecimal reworkRejectedQuantity;
    private BigDecimal pendingDispositionQuantity;
    private BigDecimal openReworkQuantity;
    private BigDecimal atRiskQuantity;
    private BigDecimal deviationRate;
    /** NO_POLICY、BELOW_THRESHOLD、TRIGGERED、RECOVERED。 */
    private String evaluationResult;
    private String evaluationReason;
    private String qualityEvidenceFingerprint;
    private Long caseId;
    private LocalDateTime evaluatedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
