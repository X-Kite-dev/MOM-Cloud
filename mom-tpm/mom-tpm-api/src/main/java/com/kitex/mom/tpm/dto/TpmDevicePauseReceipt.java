package com.kitex.mom.tpm.dto;

import lombok.Data;

/**
 * MDM已提交的状态回执；不是PLC ACK，也不等于MES本地暂停事务已提交。
 */
@Data
public class TpmDevicePauseReceipt {
    private String id;
    private String requestId;
    private Long tenantId;
    private String factoryCode;
    private String deviceCode;
    private String moldCode;
    private Long operatorId;
    private String evidenceJson;
    private String action;
    private java.time.LocalDateTime recordedTime;
}
