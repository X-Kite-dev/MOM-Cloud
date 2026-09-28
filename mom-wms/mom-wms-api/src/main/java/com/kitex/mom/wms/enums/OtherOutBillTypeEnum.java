package com.kitex.mom.wms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 其他出库业务类型枚举
 *
 * @author kitex
 * @since 2025-1-2 09:26:56
 */
@Getter
@AllArgsConstructor
public enum OtherOutBillTypeEnum {

    /**
     * 盘点出库
     */
    OTHER_INVENTORY("other_inventory", "盘点出库"),

    /**
     * 零售出库
     */
    OTHER_RETAIL("other_retail", "零售出库"),

    /**
     * 外协出库
     */
    OTHER_OUTSOURCE("other_outsource", "外协出库"),

    /**
     * 采购退货出库
     */
    OTHER_PMS("other_pms", "采购退供出库"),
    ;

    /**
     * 类型编码
     */
    private final String code;

    /**
     * 类型描述
     */
    private final String desc;

    /**
     * 根据类型编码获取枚举
     *
     * @param code 类型编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static OtherOutBillTypeEnum getByCode(String code) {
        for (OtherOutBillTypeEnum type : values()) {
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
