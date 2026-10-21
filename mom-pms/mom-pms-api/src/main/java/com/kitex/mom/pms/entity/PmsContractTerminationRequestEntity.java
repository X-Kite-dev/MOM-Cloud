package com.kitex.mom.pms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 已签订或执行中采购合同的受控终止申请。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pms_contract_termination_request")
@EqualsAndHashCode(callSuper = true)
public class PmsContractTerminationRequestEntity extends BaseEntity<PmsContractTerminationRequestEntity> {
    private String requestNo;
    private Long contractId;
    private String contractNo;
    private Long pmsOrderId;
    private String pmsOrderNo;
    private String originalContractStatus;
    private String originalOrderStatus;
    /**
     * SUBMITTED/REJECTED/STALE/SUCCEEDED。
     */
    private String status;
    private String submitRequestId;
    private String submitFingerprint;
    private String evidenceFingerprint;
    private String evidenceSnapshot;
    private String reason;
    private Integer receiptCount;
    private Integer activeReceiptCount;
    private Integer settlementCount;
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
