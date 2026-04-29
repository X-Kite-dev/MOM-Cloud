package com.kitex.mom.wms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum DeliverStateEnum {

    NOT_START("0", "未开始"),
    CREATE("1", "已下发"),
    ING("2", "进行中"),
    COMPLETE("3", "已完成"),
    CLOSE("4", "已关闭");

    @Getter
    private final String code;

    @Getter
    private final String name;


}
