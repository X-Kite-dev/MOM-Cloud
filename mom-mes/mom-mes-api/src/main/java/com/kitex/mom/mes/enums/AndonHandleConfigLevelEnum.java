package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 严重等级
 */
@Getter
@AllArgsConstructor
public enum AndonHandleConfigLevelEnum {

    GENERAL("general", "一般"),
    SERIOUS("serious", "严重");

    private final String code;
    private final String name;
}
