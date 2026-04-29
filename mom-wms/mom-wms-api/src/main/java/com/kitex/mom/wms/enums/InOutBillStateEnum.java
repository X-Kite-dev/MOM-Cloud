package com.kitex.mom.wms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 入库单状态枚举
 *
 * @author kitex
 * @since 2024-12-16 18:16:12
 */
@Getter
@AllArgsConstructor
public enum InOutBillStateEnum {

    /**
     * 待提交
     */
    PENDING_COMMIT("0", "待提交"),

    /**
     * 待审核
     */
    PENDING_REVIEW("1", "待审核"),

    /**
     * 已通过
     */
    PASSED("2", "已通过"),

    /**
     * 处理中-app
     */
    APP_RUNNING("11", "处理中"),

    /**
     * 已完成-app
     */
    FINISHED("12", "已完成");

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
    public static InOutBillStateEnum getByCode(String code) {
        for (InOutBillStateEnum state : values()) {
            if (state.getCode().equals(code)) {
                return state;
            }
        }
        return null;
    }

    /**
     * 判断状态是否可以修改 只有待提交状态可以修改
     *
     * @return true-可以修改，false-不可修改
     */
    public boolean isEditable() {
        return this == PENDING_COMMIT;
    }

    /**
     * 判断状态是否可以审核 只有待审核状态可以审核
     *
     * @return true-可以修改，false-不可修改
     */
    public boolean isReviewable() {
        return this == PENDING_REVIEW;
    }

    /**
     * 判断状态是否已完结 已通过的状态为完结状态
     *
     * @return true-已完结，false-未完结
     */
    public boolean isFinished() {
        return this == PASSED;
    }

}
