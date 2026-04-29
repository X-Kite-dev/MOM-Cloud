package com.kitex.mom.plan.constant;

import lombok.Getter;

/**
 * @author kitex
 * @date 2025-02-27 10:51:24
 */
@Getter
public enum IssueStatusEnum {
    UNISSUED("0", "未下发"),
    ISSUED("1", "已下发"),
    PRODUCT("2", "生产中"),
    COMPLETE("3", "已完成"),
    NOT_ISSUED("4", "已关闭");

    private final String code;
    private final String desc;

    IssueStatusEnum(String code, String desc) {
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
        for (IssueStatusEnum status : values()) {
            if (status.getCode().equals(code)) {
                return status.getDesc();
            }
        }
        // 如果没有找到匹配的code，可以返回null或者抛出异常，这里选择返回null
        return null;
    }
}
