package com.kitex.mom.mdm.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum YesOrNoStateEnum {

    NO("0", "否"),
    YES("1", "是");

    @Getter
    private final String code;

    @Getter
    private final String name;

    public static String getNameByCode(String code) {
        for (YesOrNoStateEnum state : YesOrNoStateEnum.values()) {
            if (state.getCode().equals(code)) {
                return state.getName();
            }
        }
        return null;
    }

}
