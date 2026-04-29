package com.kitex.mom.tpm.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CheckJudgeTypeEnum {

    OK("OK", "合格"),
    NG("NG", "不合格");

    @Getter
    private final String code;

    @Getter
    private final String name;

    public static String getNameByCode(String code) {
        for (CheckJudgeTypeEnum state : CheckJudgeTypeEnum.values()) {
            if (state.getCode().equals(code)) {
                return state.getName();
            }
        }
        return null;
    }

}
