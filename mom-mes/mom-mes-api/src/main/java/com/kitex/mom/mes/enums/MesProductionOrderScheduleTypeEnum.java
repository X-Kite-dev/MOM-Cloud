package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * <p>
 * 排程类型枚举
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@Getter
@AllArgsConstructor
public enum MesProductionOrderScheduleTypeEnum {

    AUTO("AUTO", "自动排程"),
    MANUAL("MANUAL", "手工排程");

    private final String code;
    private final String message;

    public static MesProductionOrderScheduleTypeEnum getByCode(String code) {
        for (MesProductionOrderScheduleTypeEnum type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return MANUAL;
    }
}