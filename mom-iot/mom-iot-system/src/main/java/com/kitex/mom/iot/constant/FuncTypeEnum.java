package com.kitex.mom.iot.constant;

import lombok.Getter;

/**
 * 功能类型枚举
 *
 * @author system
 * @date 2025-03-12
 */
public enum FuncTypeEnum {

    /**
     * 属性
     */
    PROPERTY(Constants.FuncType.PROPERTY, "属性"),

    /**
     * 服务
     */
    SERVICE(Constants.FuncType.SERVICE, "服务"),

    /**
     * 事件
     */
    EVENT(Constants.FuncType.EVENT, "事件");

    @Getter
    private final String code;

    @Getter
    private final String label;

    FuncTypeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static String labelOf(String code) {
        if (code == null) {
            return "";
        }
        for (FuncTypeEnum item : FuncTypeEnum.values()) {
            if (code.equals(item.code)) {
                return item.label;
            }
        }
        return "";
    }
} 