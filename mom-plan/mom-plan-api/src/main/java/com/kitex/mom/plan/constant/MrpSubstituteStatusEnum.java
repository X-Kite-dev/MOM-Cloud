package com.kitex.mom.plan.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum MrpSubstituteStatusEnum {
    NONE("NONE", "无替代建议"),
    PENDING("PENDING", "待确认"),
    APPROVED("APPROVED", "已批准"),
    PARTIAL_APPROVED("PARTIAL_APPROVED", "部分批准"),
    REJECTED("REJECTED", "已拒绝"),
    CANCELLED("CANCELLED", "决策已撤销"),
    POLICY_CONFLICT("POLICY_CONFLICT", "BOM路径策略冲突"),
    MULTILEVEL_RECALC_REQUIRED("MULTILEVEL_RECALC_REQUIRED", "自制中间件替代需受控重算");

    private final String code;
    private final String name;
}
