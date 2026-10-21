package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("mdm_device_recovery_receipt")
public class MdmDeviceRecoveryReceipt extends BaseEntity<MdmDeviceRecoveryReceipt> {
    private String factoryCode;
    private Long verificationId;
    private Long deviceId;
    private String deviceCode;
    private String requestId;
    private String requestFingerprint;
    private String beforeJson;
    private String beforeFingerprint;
    private String afterJson;
    private String sourceJson;
    private String sourceFingerprint;
    private Long actorId;
    private String actorCode;
    private String reason;
    private Boolean trialConfirmed;
    private Boolean safetyConfirmed;
    private Boolean productionHandoverConfirmed;
}
