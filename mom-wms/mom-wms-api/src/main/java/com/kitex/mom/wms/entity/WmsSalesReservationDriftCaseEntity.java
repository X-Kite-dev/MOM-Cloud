package com.kitex.mom.wms.entity;

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
 * 销售正式预留质量、位置、效期或实物漂移的受控处置待办。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_sales_reservation_drift_case")
@EqualsAndHashCode(callSuper = true)
public class WmsSalesReservationDriftCaseEntity extends BaseEntity<WmsSalesReservationDriftCaseEntity> {
    private String caseNo;
    private Long reservationId;
    private String reservationNo;
    private Long deliverPlanId;
    private String deliverPlanCode;
    private String orderCode;
    private Long orderDetailId;
    private String customerCode;
    private String productCode;
    private String unitCode;
    private BigDecimal activeQuantity;
    private BigDecimal healthyQuantity;
    private BigDecimal driftQuantity;
    private String evidenceFingerprint;
    private String evidenceSnapshot;
    private Integer detectionCount;
    private LocalDateTime firstDetectedTime;
    private LocalDateTime lastDetectedTime;
    private String status;
    private String proposalPolicy;
    private String proposalReason;
    private String proposedEvidenceFingerprint;
    private String submittedBy;
    private String submittedByName;
    private LocalDateTime submittedTime;
    private String reviewDecision;
    private String reviewComment;
    private String reviewerCode;
    private String reviewerName;
    private LocalDateTime reviewTime;
    private String reallocationRequestId;
    private String resolutionEvidenceFingerprint;
    private String resolutionEvidenceSnapshot;
    private LocalDateTime resolvedTime;
    private String lastRequestId;
    private String lastOperationType;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
