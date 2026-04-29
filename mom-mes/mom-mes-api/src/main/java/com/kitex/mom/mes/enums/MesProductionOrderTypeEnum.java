package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * <p>
 * 生产订单类型枚举
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@Getter
@AllArgsConstructor
public enum MesProductionOrderTypeEnum {

    STANDARD("STANDARD", "标准订单"),
    URGENT("URGENT", "紧急订单"),
    TRIAL("TRIAL", "试制订单");

    private final String code;
    private final String message;

    public static MesProductionOrderTypeEnum getByCode(String code) {
        for (MesProductionOrderTypeEnum type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return STANDARD;
    }
}