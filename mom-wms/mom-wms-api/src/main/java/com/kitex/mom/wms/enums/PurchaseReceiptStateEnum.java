package com.kitex.mom.wms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 采购收料单状态枚举
 *
 * @author kitex
 * @since 2025-1-22 15:32:55
 */
@Getter
@AllArgsConstructor
public enum PurchaseReceiptStateEnum {

    /**
     * 待处理
     */
    PENDING("0", "待处理"),

    /**
     * 处理中
     */
    HANDLING("1", "处理中"),

    /**
     * 质检提交
     */
    QUALITY_COMMIT("2", "质检提交"),

    /**
     * 质检完成
     */
    QUALITY_FINISHED("3", "质检完成"),

    /**
     * 已完成
     */
    COMPLETED("4", "已完成");

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
    public static PurchaseReceiptStateEnum getByCode(String code) {
        for (PurchaseReceiptStateEnum state : values()) {
            if (state.getCode().equals(code)) {
                return state;
            }
        }
        return null;
    }

}
