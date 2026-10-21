package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 报工单据状态。原报工记录永久保留，冲销通过状态和反向事务表达。
 */
@Getter
@AllArgsConstructor
public enum ReportStatusEnum {

    EFFECTIVE("有效"),
    REVERSED("已冲销");

    private final String name;
}
