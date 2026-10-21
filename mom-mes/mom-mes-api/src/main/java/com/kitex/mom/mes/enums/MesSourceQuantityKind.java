package com.kitex.mom.mes.enums;

/**
 * MES执行数量回写APS来源计划时的独立业务口径。
 * 不合格上报与QMS判退必须分开，避免同一质量事实被含混地重复累计。
 */
public enum MesSourceQuantityKind {
    UNQUALIFIED_REPORT,
    QUALITY_ISOLATED,
    QUALITY_RELEASED,
    QUALITY_REJECTED,
    SCRAP,
    REWORK_CREATED,
    REWORK_COMPLETED,
    REWORK_QUALIFIED,
    REWORK_REJECTED
}
