package com.kitex.mom.wms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ShowHiddenStateEnum {

    CREATE("0", "新建"),
    CLOSE("1", "已关闭");

    @Getter
    private final String code;

    @Getter
    private final String name;


}
