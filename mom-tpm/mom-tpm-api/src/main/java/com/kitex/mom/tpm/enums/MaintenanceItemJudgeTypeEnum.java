package com.kitex.mom.tpm.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum MaintenanceItemJudgeTypeEnum {

    CONFIRM("0", "直接确认"),
    VAL("1", "值");

    @Getter
    private final String code;

    @Getter
    private final String name;

    public static String getNameByCode(String code) {
        for (MaintenanceItemJudgeTypeEnum state : MaintenanceItemJudgeTypeEnum.values()) {
            if (state.getCode().equals(code)) {
                return state.getName();
            }
        }
        return null;
    }

    public static String getCodeByName(String name) {
        for (MaintenanceItemJudgeTypeEnum state : MaintenanceItemJudgeTypeEnum.values()) {
            if (state.getName().equals(name)) {
                return state.getCode();
            }
        }
        return null;
    }

}
