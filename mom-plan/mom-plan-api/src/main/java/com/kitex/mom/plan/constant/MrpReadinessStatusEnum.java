package com.kitex.mom.plan.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum MrpReadinessStatusEnum {
    READY("READY", "库存齐套"),
    SHORTAGE("SHORTAGE", "缺料"),
    SUBSTITUTE_PENDING("SUBSTITUTE_PENDING", "替代待确认"),
    SUBSTITUTE_APPROVED("SUBSTITUTE_APPROVED", "替代已齐套"),
    PARTIAL_SHORTAGE("PARTIAL_SHORTAGE", "部分替代仍缺料");

    private final String code;
    private final String name;
}
