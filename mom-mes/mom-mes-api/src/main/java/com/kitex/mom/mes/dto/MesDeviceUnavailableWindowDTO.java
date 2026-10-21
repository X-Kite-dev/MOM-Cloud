package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@Schema(description = "MES设备运行门不可用原因快照")
public class MesDeviceUnavailableWindowDTO {

    private String sourceType;
    private Long sourceId;
    private String sourceCode;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Boolean openEnded;
    private String state;
    private String reason;
}
