package com.kitex.mom.pms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 采购订单明细字段
 *
 * @author kitex
 * @date 2025-2-8 16:59:28
 **/
@Getter
@AllArgsConstructor
public enum PmsPlanFlowFieldDetailEnum {
    /**
     * 物料编码
     */
    MAT_CODE("matCode", "物料编码"),
    /**
     * 物料名称
     */
    MAT_NAME("matName", "物料名称"),
    /**
     * 规格型号
     */
    MAT_SPEC("matSpec", "规格型号"),
    /**
     * 要货时间
     */
    DELIVERY_TIME("deliveryTime", "要货时间"),
    /**
     * 采购计划号
     */
    ORDER_NUM("orderNum", "采购数量");

    /**
     * 编码
     */
    private final String code;

    /**
     * 流程名称
     */
    private final String name;

    /**
     * 根据类型编码获取枚举
     *
     * @param code 类型编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static PmsPlanFlowFieldDetailEnum getByCode(String code) {
        for (PmsPlanFlowFieldDetailEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }

}
