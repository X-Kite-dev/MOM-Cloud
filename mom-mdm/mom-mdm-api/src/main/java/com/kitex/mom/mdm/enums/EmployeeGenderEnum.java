package com.kitex.mom.mdm.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum EmployeeGenderEnum {

    MALE("1", "男"),
    FEMALE("2", "女");

    @Getter
    private final String code;

    @Getter
    private final String name;

    /**
     * 根据code获取name
     *
     * @param code
     * @return
     */
    public static String getNameByCode(String code) {
        for (EmployeeGenderEnum gender : EmployeeGenderEnum.values()) {
            if (gender.getCode().equals(code)) {
                return gender.getName();
            }
        }
        return null;
    }

    /**
     * 根据name获取code
     *
     * @param name
     * @return
     */
    public static String getCodeByName(String name) {
        for (EmployeeGenderEnum gender : EmployeeGenderEnum.values()) {
            if (gender.getName().equals(name)) {
                return gender.getCode();
            }
        }
        return null;
    }

}