package com.kitex.mom.tpm.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum MaintenancePlanNoticeTypeEnum {

    SYS("0", "业务系统"),
    DING("1", "钉钉"),
    ALL("2", "全部");

    @Getter
    private final String code;

    @Getter
    private final String name;

    public static String getNameByCode(String code) {
        for (MaintenancePlanNoticeTypeEnum state : MaintenancePlanNoticeTypeEnum.values()) {
            if (state.getCode().equals(code)) {
                return state.getName();
            }
        }
        return null;
    }

}
