package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_transfer_variance_case")
@EqualsAndHashCode(callSuper = true)
public class WmsTransferVarianceCaseEntity extends BaseEntity<WmsTransferVarianceCaseEntity> {
    private String caseNo;
    private Long allocationBillId;
    private String allocationBillNo;
    private String sourceWarehouseCode;
    private String sourceWarehouseName;
    private String targetWarehouseCode;
    private String targetWarehouseName;
    private Long finalReceiptId;
    private String varianceType;
    private String severity;
    private Integer varianceLineCount;
    private Integer impactedMaterialCount;
    private Boolean planReviewRequired;
    private Boolean qualityReviewRequired;
    private String reasonCode;
    private String reasonDescription;
    private String responsibleType;
    private String evidence;
    private String status;
    private String reporterCode;
    private String reporterName;
    private LocalDateTime reportedTime;
    private String warehouseDecision;
    private String warehouseReviewComment;
    private String warehouseReviewerCode;
    private String warehouseReviewerName;
    private LocalDateTime warehouseReviewTime;
    private String planDecision;
    private String planReviewComment;
    private String planReviewerCode;
    private String planReviewerName;
    private LocalDateTime planReviewTime;
    private String qualityDecision;
    private String qualityReviewComment;
    private String qualityReviewerCode;
    private String qualityReviewerName;
    private LocalDateTime qualityReviewTime;
    private String rejectedStage;
    private String shortDisposition;
    private String overDisposition;
    private String dispositionReferenceNo;
    private String dispositionRemark;
    private Long supplementAllocationBillId;
    private String resolvedBy;
    private String resolvedByName;
    private LocalDateTime resolvedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
