package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 工序任务状态枚举
 *
 * @author kitex
 * @since 2025-05-06
 */
@Getter
@AllArgsConstructor
public enum MesProduceTaskStatusEnum {

    WAIT("待派工"),
    UN_RECEIVE("待接收"),
    RECEIVE("已接收"),
    PRODUCT("生产中"),
    PAUSE("暂停中"),
    CLOSE("关闭"),
    FINISH("已完成");

    private final String name;
}
