package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 线边库物料接收状态
 *
 * @author kitex
 * @date 2025-1-2 16:02:49
 */
@Getter
@AllArgsConstructor
public enum MesMaterialInStateEnum {

    PENDING("0", "待入库"),
    PARTIAL("2", "部分签收"),
    ALREADY("1", "已入库");

    /**
     * 编码
     */
    private final String code;

    /**
     * 描述
     */
    private final String name;

    /**
     * 根据类型编码获取枚举
     *
     * @param code 类型编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static MesMaterialInStateEnum getByCode(String code) {
        for (MesMaterialInStateEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
