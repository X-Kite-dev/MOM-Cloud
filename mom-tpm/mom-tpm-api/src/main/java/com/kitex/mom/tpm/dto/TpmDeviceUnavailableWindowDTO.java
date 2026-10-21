package com.kitex.mom.tpm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@Schema(description = "设备不可用窗口")
public class TpmDeviceUnavailableWindowDTO {

    private String sourceType;
    private Long sourceId;
    private String sourceCode;
    private String deviceCode;
    private String deviceName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Boolean openEnded;
    private String state;
    private String reason;
    private String factoryCode;
}
