package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 生产报工类型
 *
 * @author kitex
 * @since 2025-05-13
 */
@Getter
@AllArgsConstructor
public enum ReportRecordTypeEnum {

    REPORT("报工"),
    WRAP("包装装箱"),
    UNQUALIFIED("不合格品上报"),
    FEED("投料");


    private final String name;
}
