package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("tpm_repair_verification")
public class TpmRepairVerification extends BaseEntity<TpmRepairVerification> {
    private String factoryCode;
    private Long taskId;
    private String taskCode;
    private String deviceCode;
    private String status;
    private String sourceJson;
    private String sourceFingerprint;
    private String workJson;
    private Long applicantId;
    private String applicantCode;
    private String applicantName;
    private String reason;
    private Long verifierId;
    private String verificationReason;
}
