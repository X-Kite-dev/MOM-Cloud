package com.kitex.mom.oms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum OrderLongStateEnum {

    CREATE("0", "新建"),
    WAIT_REVIEW("1", "待评审"),
    REVIEW("2", "已评审"),
    ISSUED_PLAN("3", "已下发计划"),
    RECEIVE("4", "已接收"),
    SCHEDULE("5", "已排产"),
    WAREHOUSE("6", "已入库"),
    ISSUED_DELIVER("7", "已下发发货"),
    DELIVER("8", "已发货"),
    COMPLETE("9", "已完成");

    @Getter
    private final String code;

    @Getter
    private final String name;

    public static String getNameByCode(String code) {
        for (OrderLongStateEnum state : OrderLongStateEnum.values()) {
            if (state.getCode().equals(code)) {
                return state.getName();
            }
        }
        return null;
    }


}
