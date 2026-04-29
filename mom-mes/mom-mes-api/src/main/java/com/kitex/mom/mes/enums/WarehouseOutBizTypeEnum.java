package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 线边库出库业务类型
 *
 * @author kitex
 * @date 2025-2-24 14:34:03
 */
@Getter
@AllArgsConstructor
public enum WarehouseOutBizTypeEnum {

    FEEDING("feeding", "投料"),
    PROCESS_PRODUCT_OUT("process_product_out", "工序生产消耗"),
    PRODUCE_RETURN("produce_return", "生产退料"),
    INVENTORY_OUT("inventory_out", "盘点出库");

    /**
     * 编码
     */
    private final String code;

    /**
     * 描述
     */
    private final String name;

    /**
     * 根据状态编码获取枚举
     *
     * @param code 状态编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static WarehouseOutBizTypeEnum getByCode(String code) {
        for (WarehouseOutBizTypeEnum item : values()) {
            if (item.getCode().equals(code)) {
                return item;
            }
        }
        return null;
    }

}
