package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Accessors(chain = true)
@Schema(description = "MES设备运行门判定")
public class MesDeviceGateDecision {

    private Boolean allowed;
    private String stage;
    private String arrangeCode;
    private String produceTaskCode;
    private String worksiteCode;
    private String deviceCode;
    private String deviceName;
    private String deviceState;
    private String deviceStateName;
    private String message;
    private LocalDateTime checkTime;
    private List<MesDeviceUnavailableWindowDTO> unavailableWindows;
}
