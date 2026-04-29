package com.kitex.mom.wms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 入库类型（单据类型）
 * purchase采购入库、return生产退料、product成品、research研发、outsource外协、spare备件、inventory盘点入库
 *
 * @author kitex
 * @date 2024年12月11日 11:21:57
 **/
@Getter
@AllArgsConstructor
public enum InBillTypeEnum {

    /**
     * 采购入库
     */
    PURCHASE("purchase", "采购入库"),
    /**
     * 生产退料入库
     */
    RETURN("return", "生产退料"),
    /**
     * 成品入库
     */
    PRODUCT("product", "成品入库"),
    /**
     * 销售退货
     */
    SALES_RETURN("sales_return", "销售退货"),
    /**
     * 盘点入库
     */
    INVENTORY("inventory", "盘点入库"),
    /**
     * 调拨入库
     */
    ALLOCATION("allocation", "调拨入库"),
    /**
     * 期初导入
     */
    INIT_IMPORT("init_import", "期初导入"),
    /**
     * 其他入库
     */
    OTHER_IN("other_in", "其他入库");

    private final String code;

    private final String name;

    /**
     * 根据类型编码获取枚举
     *
     * @param code 类型编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static InBillTypeEnum getByCode(String code) {
        for (InBillTypeEnum type : values()) {
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
