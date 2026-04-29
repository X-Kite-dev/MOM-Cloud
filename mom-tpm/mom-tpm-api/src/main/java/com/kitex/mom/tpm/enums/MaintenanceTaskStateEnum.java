package com.kitex.mom.tpm.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum MaintenanceTaskStateEnum {

    WAIT("0", "未处理"),
    ING("1", "处理中"),
    HANDLE("2", "已处理"),
    TIMEOUT_HANDLE("3", "超时已处理");

    @Getter
    private final String code;

    @Getter
    private final String name;

    public static String getNameByCode(String code) {
        for (MaintenanceTaskStateEnum state : MaintenanceTaskStateEnum.values()) {
            if (state.getCode().equals(code)) {
                return state.getName();
            }
        }
        return null;
    }

}
