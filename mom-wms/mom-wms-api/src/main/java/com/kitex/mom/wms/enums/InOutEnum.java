package com.kitex.mom.wms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 入库 和 出库 枚举
 *
 * @author kitex
 * @date 2024-12-19 10:18:39
 */
@Getter
@AllArgsConstructor
public enum InOutEnum {

    IN("in", "入库"), OUT("out", "出库");

    /**
     * 编码
     */
    private String code;

    /**
     * 描述
     */
    private String desc;

    /**
     * 根据状态编码获取枚举
     *
     * @param code 状态编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static InOutEnum getByCode(String code) {
        for (InOutEnum item : values()) {
            if (item.getCode().equals(code)) {
                return item;
            }
        }
        return null;
    }

}
