package com.kitex.mom.tpm.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum MaintenancePlanTypeEnum {

    ONCE("0", "单次保养"),
    CYCLE("1", "周期保养");

    @Getter
    private final String code;

    @Getter
    private final String name;

    public static String getNameByCode(String code) {
        for (MaintenancePlanTypeEnum state : MaintenancePlanTypeEnum.values()) {
            if (state.getCode().equals(code)) {
                return state.getName();
            }
        }
        return null;
    }

}
