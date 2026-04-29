package com.kitex.mom.wms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 业务单据每局
 *
 * @author kitex
 * @date 2025-01-08 14:48:31
 **/
@Getter
@AllArgsConstructor
public enum DocumentEnum {

    /**
     * 采购
     */
    PMS("pms", "采购"),

    /**
     * 发货
     */
    DELIVER("deliver", "发货"),

    /**
     * 退货
     */
    D_RETURN("deliver_return", "退货"),

    /**
     * 生产领料单
     */
    MES_PICKING("mes_picking", "生产领料单"),

    /**
     * 生产工单
     */
    MES_TASK("mes_task", "生产工单"),

    /**
     * 采购收料单
     */
    PURCHASE_RECEIPT("wms_purchase_receipt", "采购收料单");

    private final String code;

    private final String name;

    /**
     * 根据类型编码获取枚举
     *
     * @param code 类型编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static DocumentEnum getByCode(String code) {
        for (DocumentEnum type : values()) {
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
