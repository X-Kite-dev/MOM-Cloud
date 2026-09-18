package com.kitex.mom.pms.enums;

import lombok.Getter;

/**
 * 合同状态枚举
 *
 * @author kitex
 * @date 2025-04-03 13:37:09
 */
@Getter
public enum PmsContractStatusEnum {
    ADD("0", "起草"),
    SIGN("1", "已签订"),
    EXECUTION("2", "执行中"),
    CHANGE("3", "变更中"),
    COMPLETE("4", "结算完成"),
    ARCHIVIST("5", "归档"),
    TERMINATED("6", "已终止");

    private final String code;
    private final String desc;

    PmsContractStatusEnum(String code, String desc) {
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
        for (PmsContractStatusEnum status : values()) {
            if (status.getCode().equals(code)) {
                return status.getDesc();
            }
        }
        // 如果没有找到匹配的code，可以返回null或者抛出异常，这里选择返回null
        return null;
    }
}
