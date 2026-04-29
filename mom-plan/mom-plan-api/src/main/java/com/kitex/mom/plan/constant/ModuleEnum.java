package com.kitex.mom.plan.constant;

import lombok.Getter;

@Getter
public enum ModuleEnum {
    PMS("pms", "采购模块"),
    MES("mes", "生产执行模块");

    private final String code;
    private final String desc;

    ModuleEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

}
