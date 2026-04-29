package com.kitex.mom.pms.enums;

import lombok.Getter;

/**
 * 采购计划状态枚举
 *
 * @author kitex
 * @date 2025-02-28 10:36:59
 */
@Getter
public enum PmsPlanStatusEnum {
    NO_CREATEK("0", "未下发"),
    CREATEING("1", "下发中"),
    CREATE("2", "已下发"),
    COMPLETE("3", "完成");

    private final String code;
    private final String desc;

    PmsPlanStatusEnum(String code, String desc) {
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
        for (PmsPlanStatusEnum status : values()) {
            if (status.getCode().equals(code)) {
                return status.getDesc();
            }
        }
        // 如果没有找到匹配的code，可以返回null或者抛出异常，这里选择返回null
        return null;
    }
}
