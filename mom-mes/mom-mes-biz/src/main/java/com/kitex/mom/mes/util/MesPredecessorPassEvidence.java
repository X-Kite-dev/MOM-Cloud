package com.kitex.mom.mes.util;

import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** 扫码预览、GOOD及新NG共用的原正常轮次过站事实口径，不替代质量放行或实物转移账。 */
public final class MesPredecessorPassEvidence {
    private MesPredecessorPassEvidence() {
    }

    /** 原字段NULL只按旧报告数量解释；明确纠正至0或冲销后不再是有效过站。 */
    public static boolean effective(MesTaskReportEntity report) {
        if (report == null || report.getId() == null
                || report.getStatus() != null && !"EFFECTIVE".equals(report.getStatus())) {
            return false;
        }
        BigDecimal quantity = report.getEffectiveReportNum() == null
                ? report.getReportNum() : report.getEffectiveReportNum();
        return quantity != null && quantity.signum() > 0;
    }

    public static boolean matches(MesTaskReportEntity report, MesProduceTaskEntity predecessor, String identifier) {
        return effective(report) && predecessor != null && identifier != null && !identifier.isBlank()
                && report.getRouteReturnId() == null
                && Objects.equals(report.getTenantId(), predecessor.getTenantId())
                && Objects.equals(report.getFactoryCode(), predecessor.getFactoryCode())
                && Objects.equals(report.getTaskCode(), predecessor.getTaskCode())
                && Objects.equals(report.getProduceTaskCode(), predecessor.getCode())
                && Objects.equals(report.getTransferCode(), identifier);
    }

    /** 返回缺证据的实际前序，供页面显示节点/任务，不返回含义混淆的主工序编码集合。 */
    public static List<MesProduceTaskEntity> missing(List<MesProduceTaskEntity> predecessors,
                                                    List<MesTaskReportEntity> reports, String identifier) {
        return predecessors.stream().filter(parent -> reports.stream()
                .noneMatch(report -> matches(report, parent, identifier))).toList();
    }
}
