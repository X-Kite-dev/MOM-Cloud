package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 线边库入库业务类型
 *
 * @author kitex
 * @date 2025-2-24 14:34:03
 */
@Getter
@AllArgsConstructor
public enum WarehouseInBizTypeEnum {

    RECEPTION("reception", "物料接收"),
    PROCESS_PRODUCT_IN("process_product_in", "工序生产入库"),
    PROCESS_PRODUCT_OUT("process_product_out", "工序生产出库"),
    BACK_FLUSH_OUT("back_flush_out", "倒冲出库"),
    SURPLUS("surplus", "余料入库"),
    INVENTORY_IN("inventory_in", "盘点入库");

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
    public static WarehouseInBizTypeEnum getByCode(String code) {
        for (WarehouseInBizTypeEnum item : values()) {
            if (item.getCode().equals(code)) {
                return item;
            }
        }
        return null;
    }

}
