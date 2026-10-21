package com.kitex.mom.mdm.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * PLAN核验MDM独立恢复事实及设备当前资格的最小内网证据。
 */
@Data
public class MdmDeviceRecoveryEventEvidence {
    private Long tenantId;
    private String factoryCode;
    private Long receiptId;
    private Long latestRecoveryReceiptId;
    private Long verificationId;
    private Long deviceId;
    private String deviceCode;
    private Long taskId;
    private String taskCode;
    private Long actorId;
    private LocalDateTime recoveryTime;
    private String requestFingerprint;
    private String sourceFingerprint;
    private Boolean deviceStatus;
    private String deviceState;
    private String deviceUsable;
}
