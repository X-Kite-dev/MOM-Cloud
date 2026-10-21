package com.kitex.mom.tpm.dto;

import lombok.Data;

@Data
public class TpmDevicePauseCommand {
    private String requestId;
    private String deviceCode;
    private String moldCode;
    private String reason;
}
