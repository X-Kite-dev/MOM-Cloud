package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 工位报工类型
 */
@Getter
@AllArgsConstructor
public enum WorksiteTypeEnum {

    MANUAL("手动"),
    AUTO("自动");

    private final String name;
}
