package com.kitex.mom.mdm.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum DeviceStateEnum {

    IDLE("0", "空闲"),
    MAINTENANCE("1", "保养中"),
    REPAIR("2", "维修中"),
    SCRAP("3", "已报废"),
    RUNNING("4", "进行中"),
    PAUSE("5", "暂停中");

    @Getter
    private final String code;

    @Getter
    private final String name;

    public static String getNameByCode(String code) {
        for (DeviceStateEnum state : DeviceStateEnum.values()) {
            if (state.getCode().equals(code)) {
                return state.getName();
            }
        }
        return null;
    }


}
