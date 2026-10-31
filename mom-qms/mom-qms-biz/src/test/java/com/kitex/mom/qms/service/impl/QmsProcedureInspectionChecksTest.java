package com.kitex.mom.qms.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.kitex.mom.qms.dto.inspection.InspectionItemResultRecord;
import com.kitex.mom.qms.entity.Frequency;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskDetailEntity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

class QmsProcedureInspectionChecksTest {
    @Test
    void randomSamplingRequiresOneUnambiguousBandAndValidActualThresholds() {
        var first = new Frequency().setS(2).setE(10).setN(0).setAc(0).setRe(1);
        var overlap = new Frequency().setS(5).setE(20).setN(2).setAc(0).setRe(1);
        assertThrows(
                RuntimeException.class,
                () -> QmsProcedureInspectionChecks.frequency(30, List.of(first, overlap)));
        assertThrows(
                RuntimeException.class,
                () -> QmsProcedureInspectionChecks.frequency(8, List.of(first, overlap)));
        assertThrows(
                RuntimeException.class,
                () -> QmsProcedureInspectionChecks.frequency(2, List.of(first.setN(-1))));
        assertThrows(
                RuntimeException.class,
                () ->
                        QmsProcedureInspectionChecks.sampleSize(
                                2, "RANDOM", null, null, first.setN(10).setAc(3)));
        assertThrows(
                RuntimeException.class,
                () ->
                        QmsProcedureInspectionChecks.sampleSize(
                                2, "RANDOM", null, null, first.setAc(1).setRe(1)));
        assertEquals(
                2,
                QmsProcedureInspectionChecks.sampleSize(
                        2, "RANDOM", null, null, first.setAc(0).setRe(1)));
        assertEquals(
                2, QmsProcedureInspectionChecks.sampleSize(2, "RANDOM", null, null, first.setN(0)));
        var single = QmsProcedureInspectionChecks.frequency(1, List.of(first));
        assertEquals(1, single.getN());
        assertEquals(0, single.getAc());
    }

    @ParameterizedTest
    @CsvSource({
        "RANG,0,true",
        "RANG,10,true",
        "RANG,11,false",
        "RANG,-1,false",
        "UPPER_LIMIT,10,true",
        "UPPER_LIMIT,11,false",
        "LOWER_LIMIT,0,true",
        "LOWER_LIMIT,-1,false"
    })
    void savedNumericBoundsAreInclusive(String type, double value, boolean qualified) {
        var detail =
                new QmsInspectionProcedureTaskDetailEntity()
                        .setCheckType(type)
                        .setLowerLimit(0D)
                        .setUpperLimit(10D);
        assertEquals(
                qualified,
                QmsProcedureInspectionChecks.judge(
                        detail, new InspectionItemResultRecord().setDoubleValue(value)));
    }

    @Test
    void BooleanRequiresAnExplicitVerdictAndNumericCriteriaMustBeFinite() {
        var detail = new QmsInspectionProcedureTaskDetailEntity().setCheckType("BOOL");
        assertThrows(
                RuntimeException.class,
                () -> QmsProcedureInspectionChecks.judge(detail, new InspectionItemResultRecord()));
        assertFalse(
                QmsProcedureInspectionChecks.judge(
                        detail, new InspectionItemResultRecord().setQualified(false)));
        assertTrue(
                QmsProcedureInspectionChecks.judge(
                        detail, new InspectionItemResultRecord().setQualified(true)));
        detail.setCheckType("RANG").setLowerLimit(10D).setUpperLimit(1D);
        assertThrows(RuntimeException.class, () -> QmsProcedureInspectionChecks.criteria(detail));
        detail.setLowerLimit(0D).setUpperLimit(Double.POSITIVE_INFINITY);
        assertThrows(RuntimeException.class, () -> QmsProcedureInspectionChecks.criteria(detail));
    }

    @ParameterizedTest
    @CsvSource({"0,0,0,0,false", "2,1,2,0,true", "2,1,1,2,false", "2,1,1,1,true", "2,2,0,0,false"})
    void cachedCountersAndVerdictMustAgree(
            int total, int finished, int waiting, int ng, boolean qualified) {
        var detail =
                new QmsInspectionProcedureTaskDetailEntity()
                        .setTotalNum(total)
                        .setFinishNum(finished)
                        .setWaitNum(waiting)
                        .setUnqualifiedNum(ng)
                        .setQualifiedStatus(qualified);
        assertThrows(RuntimeException.class, () -> QmsProcedureInspectionChecks.counters(detail));
    }
}
