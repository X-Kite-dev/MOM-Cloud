package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 线边库锁定来源类型
 *
 * @author kitex
 * @date 2025-2-24 14:34:03
 */
@Getter
@AllArgsConstructor
public enum WarehouseLockResourceEnum {

    MES("mes", "生产"),
    QMS("qms", "质量");

    /**
     * 编码
     */
    private final String code;

    /**
     * 描述
     */
    private final String name;

    /**
     * 根据编码获取枚举
     *
     * @param code 编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static WarehouseLockResourceEnum getByCode(String code) {
        for (WarehouseLockResourceEnum item : values()) {
            if (item.getCode().equals(code)) {
                return item;
            }
        }
        return null;
    }

}
