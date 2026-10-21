package com.kitex.mom.pms.entity;

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
 * 已审批采购订单的受控变更申请，承载整单撤回、指定行撤回及通用商业变更。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pms_order_change_request")
@EqualsAndHashCode(callSuper = true)
public class PmsOrderChangeRequestEntity extends BaseEntity<PmsOrderChangeRequestEntity> {
    private String requestNo;
    /**
     * CANCEL_REMAINING、CANCEL_LINES、AMEND_ORDER。
     */
    private String changeType;
    private Long pmsOrderId;
    private String pmsOrderNo;
    private String originalOrderStatus;
    private String resultOrderStatus;
    /**
     * SUBMITTED/REJECTED/STALE/SUCCEEDED。
     */
    private String status;
    private String submitRequestId;
    private String submitFingerprint;
    /**
     * 外部编排来源类型；OMS级联申请固定为OMS_SUPPLY_REPLAN。
     */
    private String externalSourceType;
    /**
     * 外部编排来源业务主键。
     */
    private String externalSourceId;
    /**
     * 外部编排来源业务单号。
     */
    private String externalSourceNo;
    private String evidenceFingerprint;
    private String evidenceSnapshot;
    private String reason;
    private Integer changeVersion;
    private String originalSupplierCode;
    private String originalSupplierName;
    private String targetSupplierCode;
    private String targetSupplierName;
    private BigDecimal totalOrderQuantity;
    private BigDecimal totalTargetQuantity;
    private BigDecimal totalQuantityDelta;
    private BigDecimal totalArrivalQuantity;
    private BigDecimal totalCancelledQuantity;
    private Integer receiptCount;
    private Integer activeReceiptCount;
    private Integer contractCount;
    private Integer settlementCount;
    private Integer activeCommitmentCount;
    private Integer supplyAffectedLineCount;
    private String applicantCode;
    private String applicantName;
    private LocalDateTime submittedTime;
    private String reviewRequestId;
    private String reviewFingerprint;
    private Boolean approved;
    private String reviewReason;
    private String reviewerCode;
    private String reviewerName;
    private LocalDateTime reviewedTime;
    private LocalDateTime completedTime;
    private String resultSnapshot;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
