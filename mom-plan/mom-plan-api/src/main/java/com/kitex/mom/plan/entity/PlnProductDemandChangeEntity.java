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
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 人工产品需求的受控变更申请、冻结证据与正式回执。 */
@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("pln_product_demand_change")
public class PlnProductDemandChangeEntity extends BaseEntity<PlnProductDemandChangeEntity> {
    private String changeNo;
    private Long demandId;
    private String demandNo;
    private String submitRequestId;
    private String requestFingerprint;
    private String evidenceFingerprint;
    private String evidenceSnapshot;
    private String beforeSnapshot;
    private String afterSnapshot;
    /** ACCEPTED_NO_MRP 或 MRP_DRAFT_RECALCULATE。 */
    private String applyMode;
    private BigDecimal targetQuantity;
    private LocalDate targetDemandDate;
    private String targetWarehouseCode;
    private String targetWarehouseName;
    private Integer targetMrpPriority;
    private String reason;
    /** SUBMITTED、APPROVED、REJECTED、CANCELLED、STALE。 */
    private String status;
    private String applicantCode;
    private String applicantName;
    private LocalDateTime submittedTime;
    private String reviewRequestId;
    private Boolean approved;
    private String reviewReason;
    private String staleReason;
    private String reviewerCode;
    private String reviewerName;
    private LocalDateTime reviewedTime;
    private LocalDateTime appliedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
