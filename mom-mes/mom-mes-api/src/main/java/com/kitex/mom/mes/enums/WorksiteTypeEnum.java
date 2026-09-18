package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Locale;

/**
 * 工位报工类型
 */
@Getter
@AllArgsConstructor
public enum WorksiteTypeEnum {

    MANUAL("手动"),
    AUTO("自动");

    private final String name;

    public static WorksiteTypeEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            return MANUAL;
        }
        try {
            return valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("不支持的报工触发类型: " + code);
        }
    }
}
