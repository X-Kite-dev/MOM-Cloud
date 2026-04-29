package com.kitex.mom.mdm.enums;

import lombok.Getter;

/**
 * 供应商审核状态枚举
 *
 * @author kitex
 * @date 2025-03-19 11:39:41
 */
@Getter
public enum SupplierCheckStatusEnum {
    NO_CHECK("0", "待审核"),
    CHECK_SUCCESS("1", "通过"),
    REJECT("2", "驳回");

    private final String code;
    private final String desc;

    SupplierCheckStatusEnum(String code, String desc) {
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
        for (SupplierCheckStatusEnum status : values()) {
            if (status.getCode().equals(code)) {
                return status.getDesc();
            }
        }
        // 如果没有找到匹配的code，可以返回null或者抛出异常，这里选择返回null
        return null;
    }
}
