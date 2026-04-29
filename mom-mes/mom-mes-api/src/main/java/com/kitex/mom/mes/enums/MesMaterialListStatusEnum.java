package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * <p>
 * 用料清单状态枚举
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@Getter
@AllArgsConstructor
public enum MesMaterialListStatusEnum {

    NEW("NEW", "新建"),
    PICKING("PICKING", "领料中"),
    COMPLETED("COMPLETED", "完成");

    private final String code;
    private final String message;

    public static MesMaterialListStatusEnum getByCode(String code) {
        for (MesMaterialListStatusEnum status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return NEW;
    }
}