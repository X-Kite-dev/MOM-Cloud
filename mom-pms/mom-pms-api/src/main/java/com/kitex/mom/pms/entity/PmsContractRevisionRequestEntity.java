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
 * 已签订或执行中采购合同的正文版本变更申请。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pms_contract_revision_request")
@EqualsAndHashCode(callSuper = true)
public class PmsContractRevisionRequestEntity extends BaseEntity<PmsContractRevisionRequestEntity> {
    private String requestNo;
    private Long contractId;
    private String contractNo;
    private String pmsOrderNo;
    private String originalContractStatus;
    private Integer baseVersion;
    private Integer targetVersion;
    /**
     * SUBMITTED/REJECTED/WITHDRAWN/STALE/SUCCEEDED。
     */
    private String status;
    private String submitRequestId;
    private String submitFingerprint;
    private String evidenceFingerprint;
    private String evidenceSnapshot;
    private String originalContentSnapshot;
    private String originalContentFingerprint;
    private String targetContentSnapshot;
    private String targetContentFingerprint;
    private String changeSummary;
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
    private String withdrawRequestId;
    private String withdrawFingerprint;
    private String withdrawReason;
    private LocalDateTime withdrawnTime;
    private LocalDateTime completedTime;
    private String resultSnapshot;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
