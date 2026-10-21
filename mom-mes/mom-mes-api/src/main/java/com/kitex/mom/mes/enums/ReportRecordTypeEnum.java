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
    REVERSE("反报工/冲销"),
    QUANTITY_CORRECTION("报工数量纠正"),
    WRAP("包装装箱"),
    UNQUALIFIED("不合格品上报"),
    QUALITY_SYNC("质量数量同步"),
    FEED("投料");


    private final String name;
}
