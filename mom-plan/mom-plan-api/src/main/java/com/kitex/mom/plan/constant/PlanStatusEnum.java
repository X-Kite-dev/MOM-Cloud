package com.kitex.mom.plan.constant;

import lombok.Getter;

/**
 * @author kitex
 * @date 2024-12-12 15:56:10
 */
@Getter
public enum PlanStatusEnum {
    CANCEL("0", "未接收"),
    RECEIVE("1", "已接收"),
    MRP("2", "已生成mrp"),

    ISSUED("1", "已下达"),
    NOT_ISSUED("0", "未下达");;

    private final String code;
    private final String desc;

    PlanStatusEnum(String code, String desc) {
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
        for (PlanStatusEnum status : values()) {
            if (status.getCode().equals(code)) {
                return status.getDesc();
            }
        }
        // 如果没有找到匹配的code，可以返回null或者抛出异常，这里选择返回null
        return null;
    }

}
