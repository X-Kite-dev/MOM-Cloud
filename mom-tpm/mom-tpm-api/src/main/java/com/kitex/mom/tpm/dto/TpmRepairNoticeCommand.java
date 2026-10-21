package com.kitex.mom.tpm.dto;

import lombok.Data;

/**
 * 结果不明的维修提醒须人工核对后按稳定请求号处理。
 */
@Data
public class TpmRepairNoticeCommand {
    private Long noticeId;
    private String requestId;
    private String action;
    private String expectedStatus;
    private String reason;
}
