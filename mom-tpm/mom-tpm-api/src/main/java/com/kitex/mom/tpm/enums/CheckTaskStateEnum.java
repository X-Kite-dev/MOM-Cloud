package com.kitex.mom.tpm.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CheckTaskStateEnum {

    WAIT("0", "待指派"),
    ASSIGNED("1", "待检验"),
    ING("2", "进行中"),
    HANDLE("3", "已完成");

    @Getter
    private final String code;

    @Getter
    private final String name;

    public static String getNameByCode(String code) {
        for (CheckTaskStateEnum state : CheckTaskStateEnum.values()) {
            if (state.getCode().equals(code)) {
                return state.getName();
            }
        }
        return null;
    }

}
