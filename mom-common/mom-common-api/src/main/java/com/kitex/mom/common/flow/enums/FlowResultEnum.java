package com.kitex.mom.common.flow.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 流程类型
 *
 * @author kitex
 * @date 2025-2-8 16:59:28
 **/
@Getter
@AllArgsConstructor
public enum FlowResultEnum {

    /**
     * 系统内置flowable
     */
    AGREE("agree", "同意"),
    /**
     * 钉钉
     */
    REFUSE("refuse", "拒绝"),
    /**
     * 系统内置flowable
     */
    CHECKING("checking", "审批中"),
    ;

    /**
     * 编码
     */
    private final String code;

    /**
     * 名称
     */
    private final String name;

    /**
     * 根据编码获取枚举
     *
     * @param code 编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static FlowResultEnum getByCode(String code) {
        for (FlowResultEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }

}
