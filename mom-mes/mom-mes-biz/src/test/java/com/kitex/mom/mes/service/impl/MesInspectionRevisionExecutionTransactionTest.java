package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionOutcomeContract;

import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.*;

/** 正式修订实际推进seq2到3；复用真实报工/质量门/应用事务，数量保持零。 */
public class MesInspectionRevisionExecutionTransactionTest {
    MesInspectionRevisionAdmissionTransactionTest f;
    MesInspectionRevisionExecutionService service;
    String intent;
    ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setup() throws Exception {
        f = new MesInspectionRevisionAdmissionTransactionTest();
        f.setup();
        intent = f.intent;
        service =
                f.f.proxy(
                        new MesInspectionRevisionExecutionService(
                                f.service,
                                f.f.session.getMapper(MesInspectionRevisionAdmissionMapper.class),
                                f.f.session.getMapper(MesInspectionRevisionCancelMapper.class),
                                f.f.session.getMapper(MesInspectionRevisionExecutionMapper.class),
                                f.f.mutex,
                                f.f.states,
                                f.f.inbox,
                                f.f.projection,
                                f.f.sources,
                                f.f.worker));
        ReflectionTestUtils.setField(
                service, "deliverySecret", MesInspectionRevisionAdmissionTransactionTest.KEY);
    }

    @AfterEach
    void clear() {
        if (f != null) f.clear();
    }

    MesInspectionRevisionExecutionReceiptDTO send(String op, String outcome) {
        var d =
                new MesInspectionRevisionExecutionDTO()
                        .setOperation(op)
                        .setRequestId(MesInspectionRevisionAdmissionTransactionTest.REQUEST)
                        .setTenantId(1L)
                        .setFactoryCode("F1")
                        .setIntent(intent)
                        .setIntentFingerprint(
                                com.kitex.mom.qms.dto.inspection
                                        .QmsInspectionRevisionExecutionContract.fingerprint(intent))
                        .setOutcome(outcome)
                        .setOutcomeFingerprint(
                                outcome == null
                                        ? null
                                        : com.kitex.mom.qms.dto.inspection
                                                .QmsInspectionRevisionExecutionContract.fingerprint(
                                                outcome))
                        .setIssuedAt(Instant.now().getEpochSecond())
                        .setNonce(UUID.randomUUID().toString());
        d.setSignature(d.sign(MesInspectionRevisionAdmissionTransactionTest.KEY));
        return service.receive(d);
    }

    MesInspectionRevisionExecutionReceiptDTO seal() {
        f.service.receive(f.command("RESERVE"));
        return send("SEAL", null);
    }

    String commit(MesInspectionRevisionExecutionReceiptDTO seal) throws Exception {
        var event = f.result.deepCopy();
        event.put("eventId", "13")
                .put("historyId", "113")
                .put("sequenceNo", 3)
                .put("qualified", false)
                .put("previousEventId", f.result.path("eventId").textValue())
                .put(
                        "previousFingerprint",
                        com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionExecutionContract
                                .fingerprint(json.writeValueAsString(f.result)))
                .put(
                        "occurredAt",
                        Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString());
        var source = (ObjectNode) event.get("source");
        source.put("result", "UN_QUALIFIED")
                .put("status", "UNHANDLED")
                .put("usableNum", 0)
                .put("handleNum", 0)
                .put("latestInspectionTime", System.currentTimeMillis());
        return QmsInspectionRevisionOutcomeContract.committed(
                intent, seal.getSealFingerprint(), json.writeValueAsString(event), new Date());
    }

    @Test
    void approvedRevisionActuallyAppliesAndLeavesQualityBlocked() throws Exception {
        var seal = seal();
        var proof = commit(seal);
        var result = send("APPLY", proof);
        assertEquals("APPLIED", result.getStatus());
        assertTrue(result.isApplied());
        assertFalse(result.isHeld());
        assertEquals(3L, f.f.states.selectById(10L).getLastSequence());
        var q =
                f.f.projection.resolve(
                        f.f.states.generation(10L, 1L, "F1"), true, null, null, new Date());
        assertEquals("UN_QUALIFIED", q.task().getResult());
        assertFalse(f.f.holds.held("T1"));
        assertEquals(
                0,
                f.f.db.queryForObject(
                        "SELECT COUNT(*) FROM mom_mes.mes_task_report", Integer.class));
        assertEquals(
                3,
                f.f.db.queryForObject(
                        "SELECT COUNT(*) FROM mom_mes.mes_inspection_event_application",
                        Integer.class));
        assertEquals(
                0,
                f.f.db.queryForObject(
                        "SELECT complete_num FROM mom_mes.mes_produce_task WHERE code='P1'",
                        Integer.class));
        var replay = send("APPLY", proof);
        assertEquals(result.getResolvedAt(), replay.getResolvedAt());
        assertEquals(
                3,
                f.f.db.queryForObject(
                        "SELECT COUNT(*) FROM mom_mes.mes_inspection_event_application",
                        Integer.class));
    }

    @Test
    void sealedExecutionCannotBeReleasedThroughOldCancel() {
        seal();
        assertThrows(RuntimeException.class, () -> f.service.receive(f.command("CANCEL")));
        assertTrue(f.f.holds.held("T1"));
    }

    @Test
    void abortBeforeSealPermanentlyPreventsLatePreparation() {
        var raw =
                QmsInspectionRevisionOutcomeContract.aborted(intent, null, 4L, "中止执行", new Date());
        var done = send("ABORT", raw);
        assertEquals("ABORTED", done.getStatus());
        assertFalse(done.isHeld());
        assertEquals("CANCELED", f.service.receive(f.command("RESERVE")).getStatus());
        assertEquals("ABORTED", send("SEAL", null).getStatus());
    }

    @Test
    void abortAfterSealRestoresOriginalQualityWithoutClaimingApplication() {
        var seal = seal();
        var raw =
                QmsInspectionRevisionOutcomeContract.aborted(
                        intent, seal.getSealFingerprint(), 4L, "中止执行", new Date());
        assertEquals("ABORTED", send("ABORT", raw).getStatus());
        assertFalse(f.f.holds.held("T1"));
        assertEquals(2L, f.f.states.selectById(10L).getLastSequence());
        assertEquals(
                "QUALIFIED",
                f.f.projection
                        .resolve(f.f.states.generation(10L, 1L, "F1"), true, null, null, new Date())
                        .task()
                        .getResult());
    }

    @Test
    void missingSealOrExistingProductionNeverApplies() throws Exception {
        f.service.receive(f.command("RESERVE"));
        f.f.db.update("UPDATE mom_mes.mes_produce_task SET complete_num=1 WHERE code='P1'");
        assertThrows(RuntimeException.class, () -> send("SEAL", null));
        assertEquals(2L, f.f.states.selectById(10L).getLastSequence());
    }

    @Test
    void appliedExecutionRejectsLateAbort() throws Exception {
        var seal = seal();
        send("APPLY", commit(seal));
        assertThrows(
                RuntimeException.class,
                () ->
                        send(
                                "ABORT",
                                QmsInspectionRevisionOutcomeContract.aborted(
                                        intent,
                                        seal.getSealFingerprint(),
                                        4L,
                                        "迟到中止",
                                        new Date())));
        assertEquals(3L, f.f.states.selectById(10L).getLastSequence());
    }
}
