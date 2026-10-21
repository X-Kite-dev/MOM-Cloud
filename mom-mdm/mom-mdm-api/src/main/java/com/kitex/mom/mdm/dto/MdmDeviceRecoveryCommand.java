package com.kitex.mom.mdm.dto;

import lombok.Data;

/**
 * 已独立验证的维修单对应设备，单独执行MDM资格恢复；不自动开机。
 */
@Data
public class MdmDeviceRecoveryCommand {
    private Long verificationId;
    private String requestId;
    private String expectedFingerprint;
    private String reason;
    private Boolean trialConfirmed;
    private Boolean safetyConfirmed;
    private Boolean productionHandoverConfirmed;
}
