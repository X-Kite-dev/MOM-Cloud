package com.kitex.mom.tpm.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SparePartsApplicationSourceEnum {

    FAULT("0", "故障更换"),
    PLAN("1", "计划更换"),
    FAULT_RETURN("2", "故障更换退回"),
    PLAN_RETURN("3", "计划更换退回");

    @Getter
    private final String code;

    @Getter
    private final String name;

    public static String getNameByCode(String code) {
        for (SparePartsApplicationSourceEnum state : SparePartsApplicationSourceEnum.values()) {
            if (state.getCode().equals(code)) {
                return state.getName();
            }
        }
        return null;
    }

}
