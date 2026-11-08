package com.kitex.mom.mdm.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import java.math.BigDecimal;

/** 物料维护与导入共用的参数边界；空值保持未维护，零值按真实数值比较。 */
public final class MdmMaterialMaintenanceValidator {

    private MdmMaterialMaintenanceValidator() {
    }

    /**
     * 校验本次提交的库存、超限比例、工时、提前期与预警天数。
     * @param row 原物料参数，不在此处补默认数值或改写历史策略
     * @throws CheckedException 参数为负、上下限倒置或启用预警但缺少天数时拒绝
     */
    public static void validate(MdmMaterialEntity row) {
        if (row == null) {
            throw new CheckedException("物料资料不能为空");
        }
        nonNegative(row.getMinStock(), "最小安全库存");
        nonNegative(row.getMaxStock(), "最大安全库存");
        if (row.getMinStock() != null && row.getMaxStock() != null
                && row.getMaxStock().compareTo(row.getMinStock()) < 0) {
            throw new CheckedException("最大安全库存不能小于最小安全库存");
        }
        nonNegative(row.getExcessRatio(), "超限比例");
        if (row.getExcessRatio() != null && row.getExcessRatio().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new CheckedException("超限比例不能超过100%");
        }
        nonNegative(row.getStandardLaborHours(), "标准人员工时");
        nonNegative(row.getStandardMachineHours(), "标准机器工时");
        nonNegative(row.getIssuanceBatch(), "发料批量");
        nonNegative(row.getChangeoverTime(), "换线时长");
        nonNegative(row.getFixedLeadTime(), "固定提前期");
        nonNegative(row.getVariableLeadTime(), "变动提前期");
        nonNegative(row.getInspectionLeadTime(), "检验提前期");
        warningDays(row.getExpiration(), row.getExpirationDay(), "有效期");
        warningDays(row.getSlowMaterial(), row.getSlowMaterialDay(), "呆滞预警");
        MdmMaterialIdentifierTemplateValidator.validate(row);
    }

    /** 不将负数、缺失或停用配置混同为零。 */
    private static void warningDays(Boolean enabled, Integer days, String label) {
        if (days != null && days < 0) {
            throw new CheckedException(label + "天数不能为负数");
        }
        if (Boolean.TRUE.equals(enabled) && days == null) {
            throw new CheckedException("启用" + label + "时必须填写天数，允许0天");
        }
    }

    private static void nonNegative(BigDecimal value, String label) {
        if (value != null && value.signum() < 0) {
            throw new CheckedException(label + "不能为负数");
        }
    }
}
