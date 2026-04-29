package com.kitex.mom.iot.constant;

import lombok.Getter;

/**
 * 数据类型枚举
 *
 * @author system
 * @date 2025-03-12
 */
public enum DataTypeEnum {

    /**
     * 整数
     */
    INT(Constants.DataType.INT, "整数"),

    /**
     * 浮点数
     */
    FLOAT(Constants.DataType.FLOAT, "浮点数"),

    /**
     * 字符串
     */
    STRING(Constants.DataType.STRING, "字符串"),

    /**
     * 布尔值
     */
    BOOL(Constants.DataType.BOOL, "布尔值"),

    /**
     * 枚举
     */
    ENUM(Constants.DataType.ENUM, "枚举"),

    /**
     * 日期
     */
    DATE(Constants.DataType.DATE, "日期"),

    /**
     * 结构体
     */
    STRUCT(Constants.DataType.STRUCT, "结构体"),

    /**
     * 数组
     */
    ARRAY(Constants.DataType.ARRAY, "数组");

    @Getter
    private final String code;

    @Getter
    private final String label;

    DataTypeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static String labelOf(String code) {
        if (code == null) {
            return "";
        }
        for (DataTypeEnum item : DataTypeEnum.values()) {
            if (code.equals(item.code)) {
                return item.label;
            }
        }
        return "";
    }
} 