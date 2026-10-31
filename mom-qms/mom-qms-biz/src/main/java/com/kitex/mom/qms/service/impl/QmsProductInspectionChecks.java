package com.kitex.mom.qms.service.impl;

import com.kitex.mom.qms.dto.QmsInspectionSamples;
import com.kitex.mom.qms.dto.inspection.InspectionItemResultRecord;
import com.kitex.mom.qms.entity.*;

import java.util.List;

/** Product检验的显式领域投影，判定及持久样本守恒由共同规则执行。 */
final class QmsProductInspectionChecks {
    private QmsProductInspectionChecks() {}

    static void require(boolean valid, String message) {
        QmsInspectionEvidenceChecks.require(valid, message);
    }

    static Frequency frequency(int batch, List<Frequency> frequencies) {
        return QmsInspectionSamplingRules.frequency(batch, frequencies);
    }

    static int sampleSize(
            int batch, String method, Integer fixed, Integer requested, Frequency frequency) {
        return QmsInspectionSamplingRules.sampleSize(batch, method, fixed, requested, frequency);
    }

    static void criteria(QmsInspectionProductTaskDetailEntity detail) {
        QmsInspectionEvidenceChecks.criteria(of(detail));
    }

    static boolean judge(
            QmsInspectionProductTaskDetailEntity detail, InspectionItemResultRecord input) {
        return QmsInspectionEvidenceChecks.judge(of(detail), input);
    }

    static void counters(QmsInspectionProductTaskDetailEntity detail) {
        QmsInspectionEvidenceChecks.counters(of(detail));
    }

    static QmsInspectionEvidenceChecks.Completion completion(
            QmsInspectionProductTaskEntity task,
            List<QmsInspectionProductTaskDetailEntity> details,
            List<QmsInspectionSamples> stored) {
        return QmsInspectionEvidenceChecks.completion(
                of(task),
                details == null
                        ? null
                        : details.stream().map(QmsProductInspectionChecks::of).toList(),
                stored);
    }

    private static QmsInspectionEvidenceChecks.Task of(QmsInspectionProductTaskEntity value) {
        return value == null
                ? null
                : new QmsInspectionEvidenceChecks.Task(
                        value.getId(),
                        value.getTenantId(),
                        value.getFactoryCode(),
                        value.getNum(),
                        value.getInspectionNum());
    }

    private static QmsInspectionEvidenceChecks.Item of(QmsInspectionProductTaskDetailEntity value) {
        return value == null
                ? null
                : new QmsInspectionEvidenceChecks.Item(
                        value.getId(),
                        value.getTaskId(),
                        value.getTenantId(),
                        value.getFactoryCode(),
                        value.getCheckType(),
                        value.getUpperLimit(),
                        value.getLowerLimit(),
                        value.getTotalNum(),
                        value.getFinishNum(),
                        value.getWaitNum(),
                        value.getUnqualifiedNum(),
                        value.getAc(),
                        value.getQualifiedStatus());
    }
}
