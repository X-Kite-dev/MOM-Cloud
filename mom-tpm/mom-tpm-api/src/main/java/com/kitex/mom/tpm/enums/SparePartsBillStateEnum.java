package com.kitex.mom.tpm.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SparePartsBillStateEnum {

    CREATE("0", "新建"),
    PASS("1", "已通过"),
    CANCEL("2", "已作废");

    @Getter
    private final String code;

    @Getter
    private final String name;

    public static String getNameByCode(String code) {
        for (SparePartsBillStateEnum state : SparePartsBillStateEnum.values()) {
            if (state.getCode().equals(code)) {
                return state.getName();
            }
        }
        return null;
    }

}
