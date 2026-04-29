package com.kitex.mom.wms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum DeliverReturnStateEnum {

    CREATE("0", "新建"),
    PASS("1", "通过"),
    CLOSE("2", "已关闭");

    @Getter
    private final String code;

    @Getter
    private final String name;


}
