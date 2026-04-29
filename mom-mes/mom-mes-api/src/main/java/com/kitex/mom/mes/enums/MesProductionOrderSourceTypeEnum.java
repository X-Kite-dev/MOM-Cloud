package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * <p>
 * 生产订单来源类型枚举
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@Getter
@AllArgsConstructor
public enum MesProductionOrderSourceTypeEnum {

    MANUAL("MANUAL", "手工创建"),
    PLAN("PLAN", "计划生成"),
    SALE("SALE", "销售订单");

    private final String code;
    private final String message;

    public static MesProductionOrderSourceTypeEnum getByCode(String code) {
        for (MesProductionOrderSourceTypeEnum type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return MANUAL;
    }
}