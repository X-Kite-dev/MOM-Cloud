package com.kitex.mom.pms.enums;

import lombok.Getter;

/**
 * 采购订单状态枚举
 *
 * @author kitex
 * @date 2025-03-31 15:45:40
 */
@Getter
public enum PmsOrderStatusEnum {
    NO_CHECK("0", "未审核"),
    CHECKING("1", "审核中"),
    CHECK_SUCCESS("2", "审核通过"),
    RECEIVING("3", "收货中"),
    PART_RECEIVE("4", "部分到货"),
    COMPLETE("5", "完成收货"),
    REJECT("6", "驳回");

    private final String code;
    private final String desc;

    PmsOrderStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据code获取描述
     *
     * @param code 状态码
     * @return 状态描述
     */
    public static String getDescByCode(String code) {
        for (PmsOrderStatusEnum status : values()) {
            if (status.getCode().equals(code)) {
                return status.getDesc();
            }
        }
        // 如果没有找到匹配的code，可以返回null或者抛出异常，这里选择返回null
        return null;
    }
}
