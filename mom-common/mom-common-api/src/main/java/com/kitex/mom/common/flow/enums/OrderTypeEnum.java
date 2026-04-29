package com.kitex.mom.common.flow.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 单据类型
 *
 * @author kitex
 * @date 2025-2-8 16:59:28
 **/
@Getter
@AllArgsConstructor
public enum OrderTypeEnum {

    /**
     * 采购订单审批
     */
    PMS_ORDER("pmsOrder", "采购订单审批");

    /**
     * 编码
     */
    private final String code;

    /**
     * 流程名称
     */
    private final String flowName;

    /**
     * 根据类型编码获取枚举
     *
     * @param code 类型编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static OrderTypeEnum getByCode(String code) {
        for (OrderTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }

}
