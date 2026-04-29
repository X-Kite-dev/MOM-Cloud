package com.kitex.mom.wms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum DeliverBaseSourceEnum {

    PLAN("plan", "计划"),
    ADD("add", "新增");

    @Getter
    private final String code;

    @Getter
    private final String name;


}
