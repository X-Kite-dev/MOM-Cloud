package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.mom.mes.entity.MesExecutionTemplateEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 派工与试算共用的纯匹配规则。只选择适用模板，不代替后续工程/节点与现场执行门。
 */
final class MesExecutionTemplateMatcher {
    private MesExecutionTemplateMatcher() {
    }

    static List<String> reasons(MesExecutionTemplateEntity t, MesTaskEntity task, MesTaskArrangeEntity arrange,
                                LocalDateTime at) {
        List<String> reasons = new ArrayList<>();
        if (!"PUBLISHED".equals(t.getStatus())) {
            reasons.add("模板尚未发布或已经退役");
        }
        if (t.getEffectiveFrom() != null && at.isBefore(t.getEffectiveFrom())) {
            reasons.add("尚未到生效时间");
        }
        if (t.getEffectiveTo() != null && !at.isBefore(t.getEffectiveTo())) {
            reasons.add("已经到达失效时间");
        }
        mismatch(reasons, "物料", t.getMaterialCode(), arrange.getMatCode());
        mismatch(reasons, "主工序", t.getRouteCode(), arrange.getRouteCode());
        mismatch(reasons, "工艺路线", t.getProcessRouteCode(), task.getProcessRouteCode());
        mismatch(reasons, "稳定节点", t.getRouteNodeCode(), arrange.getSourceRouteNodeCode());
        if (t.getRouteNodeCode() != null && !"NODE_EXECUTION_V1".equals(arrange.getRouteExecutionSchemaVersion())) {
            reasons.add("精确节点模板要求原生节点执行");
        }
        mismatch(reasons, "车间", t.getWorkshopCode(), task.getWorkshopCode());
        if (StrUtil.isNotBlank(t.getWorksiteCode())
                && !Arrays.stream(StrUtil.blankToDefault(arrange.getWorksiteCodes(), "").split(","))
                .map(String::trim).anyMatch(t.getWorksiteCode()::equals)) {
            reasons.add("所选工位不包含模板工位" + t.getWorksiteCode());
        }
        return List.copyOf(reasons);
    }

    static int specificity(MesExecutionTemplateEntity t) {
        return (StrUtil.isNotBlank(t.getMaterialCode()) ? 1 : 0) + (StrUtil.isNotBlank(t.getRouteCode()) ? 1 : 0)
                + (StrUtil.isNotBlank(t.getProcessRouteCode()) ? 1 : 0)
                + (StrUtil.isNotBlank(t.getRouteNodeCode()) ? 1 : 0)
                + (StrUtil.isNotBlank(t.getWorkshopCode()) ? 1 : 0) + (StrUtil.isNotBlank(t.getWorksiteCode()) ? 1 : 0);
    }

    /**
     * 保持既有选择顺序：优先级、精确度、版本、精确Long记录号，均取大。
     */
    static Comparator<MesExecutionTemplateEntity> order() {
        return Comparator.comparingInt((MesExecutionTemplateEntity t) -> Objects.requireNonNullElse(t.getPriority(), 0))
                .thenComparingInt(MesExecutionTemplateMatcher::specificity)
                .thenComparingInt(t -> Objects.requireNonNullElse(t.getVersionNo(), 0))
                .thenComparing(t -> Objects.requireNonNullElse(t.getId(), 0L));
    }

    private static void mismatch(List<String> reasons, String label, String expected, String actual) {
        if (StrUtil.isNotBlank(expected) && !Objects.equals(expected, actual)) {
            reasons.add(label + "不符合模板范围" + expected);
        }
    }
}
