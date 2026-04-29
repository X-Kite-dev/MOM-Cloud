package com.kitex.mom.wms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 其他入库业务类型
 *
 * @author kitex
 * @date 2025-1-2 09:29:36
 **/
@Getter
@AllArgsConstructor
public enum OtherInBillTypeEnum {

    /**
     * 盘点入库
     */
    OTHER_INVENTORY("other_inventory", "盘点入库"),
    ;

    private final String code;

    private final String name;

    /**
     * 根据类型编码获取枚举
     *
     * @param code 类型编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static OtherInBillTypeEnum getByCode(String code) {
        for (OtherInBillTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }

    /**
     * 判断是否是有效的单据类型
     *
     * @param code 类型编码
     * @return true-有效，false-无效
     */
    public static boolean isValid(String code) {
        return getByCode(code) != null;
    }
}
