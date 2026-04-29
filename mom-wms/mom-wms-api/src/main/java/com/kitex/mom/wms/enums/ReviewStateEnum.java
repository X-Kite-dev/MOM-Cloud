package com.kitex.mom.wms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ReviewStateEnum {

    UN_COMPLETE("0", "未完成"),
    COMPLETE("1", "已完成");

    @Getter
    private final String code;

    @Getter
    private final String name;


}
