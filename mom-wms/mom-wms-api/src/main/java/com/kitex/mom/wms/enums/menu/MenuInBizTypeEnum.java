package com.kitex.mom.wms.enums.menu;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 入库业务菜单类型
 *
 * @author kitex
 * @date 2025-1-14 10:06:59
 **/
@Getter
@AllArgsConstructor
public enum MenuInBizTypeEnum {

    /**
     * 采购入库
     */
    PURCHASE("purchase", "采购入库", "/wms/inBiz/purchase", "/pages-wms/order/inBillOrder/index", 1),
    /**
     * 生产退料入库
     */
    RETURN("return", "生产退料", "/wms/inBiz/return", "/pages-wms/order/inBillOrder/index", 2),
    /**
     * 成品入库
     */
    PRODUCT("product", "成品入库", "/wms/inBiz/product", "/pages-wms/order/inBillOrder/index", 3),
    /**
     * 销售退货
     */
    SALES_RETURN("sales_return", "销售退货", "/wms/inBiz/salesReturn", "/pages-wms/order/inBillOrder/index", 4),
    /**
     * 其他入库
     */
    OTHER_IN("other_in", "其他入库", "/wms/inBiz/other", "/pages-wms/order/other/in/index", 10);

    private final String code;

    private final String label;

    private final String value;

    private final String appPath;

    private final Integer sort;

    /**
     * 根据code获取枚举
     *
     * @param code 编码
     * @return 对应的枚举，如果不存在则返回null
     */
    public static MenuInBizTypeEnum getByCode(String code) {
        for (MenuInBizTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }

    /**
     * 根据value获取枚举
     *
     * @param value 枚举值
     * @return 对应的枚举，如果不存在则返回null
     */
    public static MenuInBizTypeEnum getByValue(String value) {
        for (MenuInBizTypeEnum type : values()) {
            if (type.getValue().equals(value)) {
                return type;
            }
        }
        return null;
    }
}
