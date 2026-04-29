package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum MesTaskTypeEnum {

    PART("零件计划"),
    PRODUCT("成品计划");

    private final String name;
}
