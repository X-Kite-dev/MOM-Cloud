package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * <p>
 * 排程状态枚举
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@Getter
@AllArgsConstructor
public enum MesProductionOrderScheduleStatusEnum {

    DRAFT("DRAFT", "草稿"),
    CONFIRMED("CONFIRMED", "确认"),
    RELEASED("RELEASED", "已下达");

    private final String code;
    private final String message;

    public static MesProductionOrderScheduleStatusEnum getByCode(String code) {
        for (MesProductionOrderScheduleStatusEnum status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return DRAFT;
    }
}