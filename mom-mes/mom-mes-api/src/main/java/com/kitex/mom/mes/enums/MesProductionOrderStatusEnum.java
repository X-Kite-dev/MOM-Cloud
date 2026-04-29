package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * <p>
 * 生产订单状态枚举
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@Getter
@AllArgsConstructor
public enum MesProductionOrderStatusEnum {

    NEW("NEW", "新建"),
    SCHEDULED("SCHEDULED", "已排程"),
    RELEASED("RELEASED", "已下达"),
    IN_PRODUCTION("IN_PRODUCTION", "生产中"),
    COMPLETED("COMPLETED", "完成"),
    CANCELLED("CANCELLED", "取消");

    private final String code;
    private final String message;

    public static MesProductionOrderStatusEnum getByCode(String code) {
        for (MesProductionOrderStatusEnum status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return NEW;
    }
}