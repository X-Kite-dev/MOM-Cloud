package com.kitex.mom.pms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 采购计划字段
 *
 * @author kitex
 * @date 2025-2-8 16:59:28
 **/
@Getter
@AllArgsConstructor
public enum PmsPlanFlowFieldEnum {

    /**
     * 采购订单号
     */
    PMS_ORDER_NO("pmsOrderNo", "采购订单号"),
    /**
     * 供应商名称
     */
    SUPPLIER_NAME("supplierName", "供应商名称"),
    /**
     * 供应商编码
     */
    SUPPLIER_CODE("supplierCode", "供应商编码"),
    /**
     * 采购计划号
     */
    PLAN_CHECK("checkReason", "审批意见");

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
    public static PmsPlanFlowFieldEnum getByCode(String code) {
        for (PmsPlanFlowFieldEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }

}
