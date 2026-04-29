package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum WrapTypeEnum {

    PRODUCT("成品"),
    HALF("半成品");

    private final String name;
}
