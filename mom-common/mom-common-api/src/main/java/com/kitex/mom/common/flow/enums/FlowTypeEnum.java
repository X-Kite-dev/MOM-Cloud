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
public enum FlowTypeEnum {

    /**
     * 系统内置flowable
     */
    FLOWABLE("flowable", "系统内置flowable"),
    /**
     * 钉钉
     */
    DING_TALK("dingTalk", "钉钉");

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
    public static FlowTypeEnum getByCode(String code) {
        for (FlowTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }

}
