package com.kitex.mom.qms.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.kitex.common.core.util.R;
import com.kitex.mom.mes.api.feign.RemoteMesInspectionProductionBasisService;
import com.kitex.mom.mes.dto.MesInspectionProductionBasisDTO;
import com.kitex.mom.mes.dto.MesInspectionProductionBasisDTO.*;
import com.kitex.mom.qms.dto.inspection.*;
import com.kitex.mom.qms.service.InspectionProcedureTaskService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class QmsInspectionProductionBasisTest {
    QmsProcedureInspectionIntegrityTest fixture;
    InspectionProcedureTaskService service;
    JdbcTemplate db;
    RemoteMesInspectionProductionBasisService remote;
    QmsInspectionProductionBasisService production;

    @BeforeEach
    void setup() throws Exception {
        fixture = new QmsProcedureInspectionIntegrityTest();
        fixture.setup();
        service = (InspectionProcedureTaskService) ReflectionTestUtils.getField(fixture, "service");
        db = (JdbcTemplate) ReflectionTestUtils.getField(fixture, "db");
        remote = mock(RemoteMesInspectionProductionBasisService.class);
        production = new QmsInspectionProductionBasisService(remote);
        var basis =
                ReflectionTestUtils.getField(
                        AopProxyUtils.getSingletonTarget(service), "inspectionBasis");
        ReflectionTestUtils.setField(basis, "production", production);
        when(remote.query(any())).thenReturn(R.ok(evidence(command(), "F1", "FROZEN", "EA")));
    }

    @AfterEach
    void close() {
        if (fixture != null) fixture.close();
    }

    static Evidence evidence(
            InspectionProcedureCreateParam p, String factory, String state, String unit) {
        boolean known = state.equals("FROZEN") || state.equals("PARTIAL");
        return new Evidence(
                MesInspectionProductionBasisDTO.SCHEMA,
                state,
                "1",
                factory,
                p.getSourceRequestId(),
                p.getSourceRequestFingerprint(),
                p.getSourceTaskCode(),
                p.getOrderNo(),
                p.getOrderId() == null ? "31" : p.getOrderId(),
                p.getMatCode(),
                p.getProcessCode(),
                String.valueOf(p.getNum()),
                known ? "9007199254740993" : null,
                known ? "2" : null,
                known ? "a".repeat(64) : null,
                known ? "RN-22" : null,
                known ? "11" : null,
                known ? "V1" : null,
                unit,
                state.equals("FROZEN")
                        ? java.util.List.of()
                        : java.util.List.of(
                                state.equals("PARTIAL")
                                        ? "QUANTITY_UNIT_MISSING"
                                        : "ORIGINAL_ENGINEERING_MISSING"));
    }

    InspectionProcedureCreateParam command() {
        var p =
                (InspectionProcedureCreateParam)
                        ReflectionTestUtils.invokeMethod(fixture, "command");
        p.setOrderId("31");
        return p.setSourceRequestFingerprint(InspectionProcedureSourceContract.fingerprint(p));
    }

    JsonNode snapshot(long task, int version) throws Exception {
        return new ObjectMapper()
                .readTree(
                        db.queryForObject(
                                "SELECT payload FROM mom_qms.qms_inspection_basis WHERE task_id=?"
                                        + " AND revision=?",
                                String.class,
                                task,
                                version));
    }

    @Test
    void missingOptionalOriginalOrderIdUsesTheVerifiedActualMesOwner() throws Exception {
        var p = command().setOrderId(null);
        p.setSourceRequestFingerprint(InspectionProcedureSourceContract.fingerprint(p));
        when(remote.query(any())).thenReturn(R.ok(evidence(p, "F1", "FROZEN", "EA")));
        long id = service.create(p);
        assertEquals("31", snapshot(id, 1).path("productionContext").path("orderId").asText());
        assertTrue(snapshot(id, 1).path("task").path("orderId").isNull());
    }

    @Test
    void creationStoresExactProductionEvidenceInsideItsRealBasisTransaction() throws Exception {
        long id = service.create(command());
        var raw = snapshot(id, 1).path("productionContext");
        assertEquals("EA", raw.path("quantityUnit").asText());
        assertEquals("RN-22", raw.path("routeNodeCode").asText());
        assertEquals("9007199254740993", raw.path("engineeringReleaseId").asText());
    }

    @Test
    void frozenMesTaskStillRejectsDirectAppendWithoutReplacingItsProductionContext()
            throws Exception {
        long id = service.create(command());
        reset(remote);
        when(remote.query(any())).thenThrow(new IllegalStateException("current data unavailable"));
        assertThrows(
                RuntimeException.class,
                () -> service.append(new InspectionAppendParam().setId(id).setNum(1)));
        assertEquals("EA", snapshot(id, 1).path("productionContext").path("quantityUnit").asText());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM mom_qms.qms_inspection_basis WHERE task_id=?",
                        Integer.class,
                        id));
    }

    @Test
    void originalRequestReplayDoesNotReplaceItsSavedContext() throws Exception {
        var p = command();
        long id = service.create(p);
        reset(remote);
        when(remote.query(any())).thenThrow(new IllegalStateException("unavailable"));
        assertEquals(id, service.create(p));
        assertEquals("EA", snapshot(id, 1).path("productionContext").path("quantityUnit").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"F2", ""})
    void changedOrMissingOwnerRejectsCreationAndLeavesNoNewTask(String factory) {
        when(remote.query(any())).thenReturn(R.ok(evidence(command(), factory, "FROZEN", "EA")));
        int before = count();
        assertThrows(RuntimeException.class, () -> service.create(command()));
        assertEquals(before, count());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM mom_qms.qms_inspection_basis", Integer.class));
    }

    @Test
    void transportFailureCannotProduceAnInspectionWithoutItsRequiredEvidence() {
        when(remote.query(any())).thenThrow(new IllegalStateException("lost"));
        int before = count();
        assertThrows(RuntimeException.class, () -> service.create(command()));
        assertEquals(before, count());
    }

    @Test
    void partialEvidenceKeepsUnitUnknownRatherThanInventingPieces() throws Exception {
        when(remote.query(any())).thenReturn(R.ok(evidence(command(), "F1", "PARTIAL", null)));
        long id = service.create(command());
        assertTrue(snapshot(id, 1).path("productionContext").path("quantityUnit").isNull());
    }

    @Test
    void oldNonMesCreationKeepsItsOriginalCanonicalSnapshot() throws Exception {
        var p = command().setSourceRequestId("LOCAL-ORIGINAL").setSourceRequestFingerprint(null);
        long id = service.create(p);
        assertFalse(snapshot(id, 1).has("productionContext"));
        service.append(new InspectionAppendParam().setId(id).setNum(1));
        assertFalse(snapshot(id, 2).has("productionContext"));
    }

    @Test
    void aFrozenStateWithAnUnknownUnitCannotBeAccepted() {
        when(remote.query(any())).thenReturn(R.ok(evidence(command(), "F1", "FROZEN", null)));
        assertThrows(RuntimeException.class, () -> service.create(command()));
    }

    @Test
    void necessaryBasisWriteReturningZeroRollsBackItsNewProductionInspection() {
        var fault = ReflectionTestUtils.getField(fixture, "fault");
        ReflectionTestUtils.setField(fault, "lose", "QmsInspectionBasisMapper.insert");
        int before = count();
        assertThrows(RuntimeException.class, () -> service.create(command()));
        assertEquals(before, count());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM mom_qms.qms_inspection_basis", Integer.class));
    }

    int count() {
        return db.queryForObject(
                "SELECT count(*) FROM mom_qms.qms_inspection_procedure_task", Integer.class);
    }
}
