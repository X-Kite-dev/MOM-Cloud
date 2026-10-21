package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum MesDeviceGateStageEnum {

    DEVICE_START("设备开机"),
    RECEIVE("任务接收"),
    START("任务开工"),
    REPORT("人工报工"),
    AUTO_REPORT("设备自动报工");

    private final String name;
}
