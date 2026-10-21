package com.kitex.mom.plan.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SchedulingMaterialStatusEnum {
    READY("READY", "物料齐套"),
    WAITING_SUPPLY("WAITING_SUPPLY", "等待已下达供应"),
    SUPPLY_OVERDUE("SUPPLY_OVERDUE", "供应已逾期未齐套"),
    SUPPLY_NOT_RELEASED("SUPPLY_NOT_RELEASED", "供应计划未下达"),
    SUBSTITUTE_PENDING("SUBSTITUTE_PENDING", "替代方案待处理"),
    POLICY_CONFLICT("POLICY_CONFLICT", "替代策略冲突"),
    RESERVATION_RISK("RESERVATION_RISK", "物料预留存在风险"),
    RESERVATION_UNVERIFIED("RESERVATION_UNVERIFIED", "物料预留暂未复核"),
    SUPPLY_EVIDENCE_UNVERIFIED("SUPPLY_EVIDENCE_UNVERIFIED", "正式供应证据暂无法复核"),
    SUPPLY_DATE_MISSING("SUPPLY_DATE_MISSING", "供应到料日期缺失"),
    BOM_LINEAGE_INVALID("BOM_LINEAGE_INVALID", "BOM逐层需求证据缺失或损坏"),
    MULTILEVEL_SUBSTITUTE_RECALC_REQUIRED("MULTILEVEL_SUBSTITUTE_RECALC_REQUIRED", "自制中间件替代需受控重算"),
    MATERIAL_OPERATION_MAPPING_MISSING("MATERIAL_OPERATION_MAPPING_MISSING", "物料工序投料映射缺失"),
    MULTILEVEL_CONVERGENCE_CONFLICT("MULTILEVEL_CONVERGENCE_CONFLICT", "多层物料汇合时刻冲突"),
    MRP_NOT_CALCULATED("MRP_NOT_CALCULATED", "尚未完成MRP运算");

    private final String code;
    private final String name;
}
