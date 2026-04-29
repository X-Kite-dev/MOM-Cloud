package com.kitex.mom.mdm.constant;

import lombok.Getter;

/**
 * 工作状态枚举
 *
 * @author kitex
 * @date 2024-11-05 09:07:23
 */
public enum WorkingStatusEnum {

    /**
     * 使用中
     */
    WORKING(Boolean.TRUE, "使用中"),

    /**
     * 未开始
     */
    STOP(Boolean.FALSE, "停止");

    @Getter
    private final Boolean code;

    @Getter
    private final String label;

    WorkingStatusEnum(Boolean code, String label) {
        this.code = code;
        this.label = label;
    }

    public static Boolean codeOf(String label) {
        if (label == null) {
            return null;
        }
        for (WorkingStatusEnum workingStatusEnum : WorkingStatusEnum.values()) {
            if (label.equals(workingStatusEnum.label)) {
                return workingStatusEnum.code;
            }
        }
        return null;
    }

    public static String labelOf(Boolean code) {
        if (code == null) {
            return "";
        }
        for (WorkingStatusEnum workingStatusEnum : WorkingStatusEnum.values()) {
            if (code.equals(workingStatusEnum.code)) {
                return workingStatusEnum.label;
            }
        }
        return "";
    }

}
