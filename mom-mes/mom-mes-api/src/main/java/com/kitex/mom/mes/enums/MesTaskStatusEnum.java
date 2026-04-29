package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 工单下达状态枚举
 *
 * @author kitex
 * @since 2025-05-06
 */
@Getter
@AllArgsConstructor
public enum MesTaskStatusEnum {

    CREATE("新建"),
    ISSUE("下达中"),
    DISPATCH("已派工"),
    PRODUCT("生产中"),
    FINISH("完成"),
    CLOSE("关闭");

    private final String name;


}
