package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * <p>
 * 物料领料状态枚举
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@Getter
@AllArgsConstructor
public enum MesMaterialPickStatusEnum {

    PENDING("PENDING", "待领料"),
    PICKING("PICKING", "领料中"),
    PICKED("PICKED", "已领料"),
    COMPLETED("COMPLETED", "完成");

    private final String code;
    private final String message;

    public static MesMaterialPickStatusEnum getByCode(String code) {
        for (MesMaterialPickStatusEnum status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return PENDING;
    }
}