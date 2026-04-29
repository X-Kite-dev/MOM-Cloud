package com.kitex.mom.oms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum OrderStateEnum {

    CREATE("0", "新建"),
    WAIT_REVIEW("1", "待评审"),
    REVIEW("2", "评审完成"),
    ISSUED("3", "已下发"),
    ISSUED_PLAN("31", "已下发计划"),
    ISSUED_DELIVER("32", "已下发发货"),
    COMPLETE("8", "已完成");

    @Getter
    private final String code;

    @Getter
    private final String name;

    public static String getNameByCode(String code) {
        for (OrderStateEnum state : OrderStateEnum.values()) {
            if (state.getCode().equals(code)) {
                return state.getName();
            }
        }
        return null;
    }


}
