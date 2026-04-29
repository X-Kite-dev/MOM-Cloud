package com.kitex.mom.tpm.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum DeviceLifeCycleEventEnum {

    ADD("add", "新增"),
    REPLACE("replace", "备件更换"),
    SCRAP("scrap", "报废"),
    POWER_ON("power_on", "开机"),
    PAUSE("pause", "暂停"),
    CLOSE("close", "关机");

    @Getter
    private final String code;

    @Getter
    private final String name;

    public static String getNameByCode(String code) {
        for (DeviceLifeCycleEventEnum state : DeviceLifeCycleEventEnum.values()) {
            if (state.getCode().equals(code)) {
                return state.getName();
            }
        }
        return null;
    }

}
