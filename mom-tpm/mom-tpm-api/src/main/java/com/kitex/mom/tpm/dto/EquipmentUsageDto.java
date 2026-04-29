package com.kitex.mom.tpm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class EquipmentUsageDto {


    @Schema(description = "设备使用率")
    private BigDecimal utilizationRate;

    @Schema(description = "空闲设备")
    private Integer idle;

    @Schema(description = "设备空闲率")
    private BigDecimal idleRate;

    @Schema(description = "运行中设备")
    private Integer running;

    @Schema(description = "设备运行率")
    private BigDecimal runningRate;

    @Schema(description = "停机设备")
    private Integer stopped;

    @Schema(description = "设备停机率")
    private BigDecimal stoppedRate;


}
