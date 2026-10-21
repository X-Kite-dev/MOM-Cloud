package com.kitex.mom.tpm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "设备不可用窗口查询条件")
public class TpmDeviceAvailabilityQueryDTO {

    @Schema(description = "设备编码集合")
    private List<String> deviceCodes;

    @Schema(description = "查询开始时间")
    private LocalDateTime startTime;

    @Schema(description = "查询结束时间")
    private LocalDateTime endTime;
}
