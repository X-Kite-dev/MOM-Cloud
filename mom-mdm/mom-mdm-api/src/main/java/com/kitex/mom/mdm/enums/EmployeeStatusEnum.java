package com.kitex.mom.mdm.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum EmployeeStatusEnum {

    ACTIVE("1", "在职"),
    INACTIVE("0", "离职");

    @Getter
    private final String code;

    @Getter
    private final String name;

    public static String getNameByCode(String code) {
        for (EmployeeStatusEnum status : EmployeeStatusEnum.values()) {
            if (status.getCode().equals(code)) {
                return status.getName();
            }
        }
        return null;
    }

}