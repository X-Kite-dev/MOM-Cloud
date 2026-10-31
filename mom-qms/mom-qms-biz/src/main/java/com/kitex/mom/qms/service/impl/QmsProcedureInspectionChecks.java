package com.kitex.mom.qms.service.impl;

import com.kitex.mom.qms.dto.QmsInspectionSamples;
import com.kitex.mom.qms.dto.inspection.InspectionItemResultRecord;
import com.kitex.mom.qms.entity.*;

import java.util.List;

/** Procedure检验的显式领域投影，判定及持久样本守恒由共同规则执行。 */
final class QmsProcedureInspectionChecks {
    private QmsProcedureInspectionChecks() {}

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

    static void criteria(QmsInspectionProcedureTaskDetailEntity detail) {
        QmsInspectionEvidenceChecks.criteria(of(detail));
    }

    static boolean judge(
            QmsInspectionProcedureTaskDetailEntity detail, InspectionItemResultRecord input) {
        return QmsInspectionEvidenceChecks.judge(of(detail), input);
    }

    static void counters(QmsInspectionProcedureTaskDetailEntity detail) {
        QmsInspectionEvidenceChecks.counters(of(detail));
    }

    static QmsInspectionEvidenceChecks.Completion completion(
            QmsInspectionProcedureTaskEntity task,
            List<QmsInspectionProcedureTaskDetailEntity> details,
            List<QmsInspectionSamples> stored) {
        return QmsInspectionEvidenceChecks.completion(
                of(task),
                details == null
                        ? null
                        : details.stream().map(QmsProcedureInspectionChecks::of).toList(),
                stored);
    }

    private static QmsInspectionEvidenceChecks.Task of(QmsInspectionProcedureTaskEntity value) {
        return value == null
                ? null
                : new QmsInspectionEvidenceChecks.Task(
                        value.getId(),
                        value.getTenantId(),
                        value.getFactoryCode(),
                        value.getNum(),
                        value.getInspectionNum());
    }

    private static QmsInspectionEvidenceChecks.Item of(
            QmsInspectionProcedureTaskDetailEntity value) {
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
