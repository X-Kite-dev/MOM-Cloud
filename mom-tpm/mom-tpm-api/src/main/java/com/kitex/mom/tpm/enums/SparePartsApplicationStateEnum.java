package com.kitex.mom.tpm.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SparePartsApplicationStateEnum {

    WAIT("0", "待审批"),
    PASS("1", "已通过"),
    REJECT("2", "已驳回");

    @Getter
    private final String code;

    @Getter
    private final String name;

    public static String getNameByCode(String code) {
        for (SparePartsApplicationStateEnum state : SparePartsApplicationStateEnum.values()) {
            if (state.getCode().equals(code)) {
                return state.getName();
            }
        }
        return null;
    }

}
