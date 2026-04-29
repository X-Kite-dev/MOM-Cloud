package com.kitex.mom.wms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 出库通知单状态枚举
 *
 * @author Cascade
 * @since 2024-12-16
 */
@Getter
@AllArgsConstructor
public enum NoticeStateEnum {

    /**
     * 待审核
     */
    PENDING_REVIEW("0", "待审核"),

    /**
     * 已审核
     */
    REVIEWED("1", "已审核"),

    /**
     * 部分出库
     */
    PARTIALLY_OUT("2", "部分出库"),

    /**
     * 完全出库
     */
    FULLY_OUT("3", "完全出库"),

    /**
     * 已取消
     */
    CANCELLED("9", "已取消");

    /**
     * 状态编码
     */
    private final String code;

    /**
     * 状态描述
     */
    private final String desc;

    /**
     * 根据状态编码获取枚举
     *
     * @param code 状态编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static NoticeStateEnum getByCode(String code) {
        for (NoticeStateEnum state : values()) {
            if (state.getCode().equals(code)) {
                return state;
            }
        }
        return null;
    }

    /**
     * 判断状态是否可以修改 只有待审核状态可以修改
     *
     * @return true-可以修改，false-不可修改
     */
    public boolean isEditable() {
        return this == PENDING_REVIEW;
    }

    /**
     * 判断状态是否已完结 完全出库或已取消的状态为完结状态
     *
     * @return true-已完结，false-未完结
     */
    public boolean isFinished() {
        return this == FULLY_OUT || this == CANCELLED;
    }

}
