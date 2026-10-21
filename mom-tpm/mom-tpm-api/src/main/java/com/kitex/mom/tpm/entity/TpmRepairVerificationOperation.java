package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("tpm_repair_verification_operation")
public class TpmRepairVerificationOperation extends BaseEntity<TpmRepairVerificationOperation> {
    private String factoryCode;
    private Long verificationId;
    private String requestId;
    private String fingerprint;
    private String action;
    private String resultStatus;
    private Long operatorId;
    private String reason;
    private Boolean faultResolved;
    private Boolean trialVerified;
    private Boolean protectionVerified;
}
