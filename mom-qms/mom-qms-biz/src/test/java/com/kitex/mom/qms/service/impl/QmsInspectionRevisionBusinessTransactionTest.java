package com.kitex.mom.qms.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.qms.dto.*;
import com.kitex.mom.qms.dto.inspection.*;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.*;
import com.kitex.mom.qms.service.*;

import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

/** QMS实际改判/历史/事件/回滚与正式run；MES端口是明确边界模拟，MES实际消费另有业务用例。 */
public class QmsInspectionRevisionBusinessTransactionTest {
    QmsInspectionRevisionCoordinationTransactionTest f;
    QmsInspectionRevisionRunService control;
    QmsInspectionRevisionCommitService commit;
    QmsInspectionRevisionRunWorker worker;
    QmsInspectionRevisionRunEngine engine;
    QmsInspectionRevisionRunMapper mapper;
    String mode = "OK";
    Map<Long, Date> sealTimes = new HashMap<>();

    @BeforeEach
    void setup() throws Exception {
        f = new QmsInspectionRevisionCoordinationTransactionTest();
        f.setup();
        mapper = f.f.f.session.getMapper(QmsInspectionRevisionRunMapper.class);
        var access =
                (QmsInspectionHistorySupport) f.f.f.deps.get(QmsInspectionHistorySupport.class);
        control =
                f.f.f.proxy(
                        new QmsInspectionRevisionRunService(mapper, f.mapper, f.service, access));
        var history =
                (InspectionProcedureHistoryService)
                        ReflectionTestUtils.getField(f.f.f.origin, "procedure");
        var plan = mock(QmsPlanEventOutboxService.class);
        when(plan.enqueueIsolation(anyString(), anyLong()))
                .thenAnswer(
                        a -> {
                            if ("LOCAL_FAIL".equals(mode))
                                throw new IllegalStateException("local plan enqueue failure");
                            return new QmsPlanEventOutboxEntity()
                                    .setEventId("QMS-ISOLATE:PROCEDURE:700:V1");
                        });
        commit =
                f.f.f.proxy(
                        new QmsInspectionRevisionCommitService(
                                mapper,
                                f.mapper,
                                f.f.f.session.getMapper(QmsInspectionEventMapper.class),
                                f.f.f.session.getMapper(QmsInspectionProcedureTaskMapper.class),
                                f.f.f.journal,
                                history,
                                plan));
        worker = f.f.f.proxy(new QmsInspectionRevisionRunWorker(mapper, f.mapper, f.mes));
        ReflectionTestUtils.setField(
                worker, "deliverySecret", QmsInspectionRevisionCoordinationTransactionTest.KEY);
        engine = new QmsInspectionRevisionRunEngine(mapper, f.worker, worker, commit);
        when(f.mes.executeInspectionRevision(any()))
                .thenAnswer(
                        a -> {
                            var d = (MesInspectionRevisionExecutionDTO) a.getArgument(0);
                            assertTrue(
                                    d.signatureMatches(
                                            QmsInspectionRevisionCoordinationTransactionTest.KEY));
                            var intent =
                                    QmsInspectionRevisionExecutionContract.parse(d.getIntent());
                            var q = intent.request();
                            String seal =
                                    QmsInspectionRevisionOutcomeContract.hash(
                                            d.getIntent() + "sealed");
                            var value =
                                    new MesInspectionRevisionExecutionReceiptDTO()
                                            .setContractVersion("MES_REVISION_EXECUTION_V1")
                                            .setRequestId(d.getRequestId())
                                            .setTenantId(1L)
                                            .setFactoryCode("F1")
                                            .setInspectionId(700L)
                                            .setTaskCode(
                                                    q.path("source").path("orderNo").textValue())
                                            .setProduceTaskCode(
                                                    q.path("source")
                                                            .path("sourceTaskCode")
                                                            .textValue())
                                            .setIntentFingerprint(d.getIntentFingerprint())
                                            .setGenRecordId(99L)
                                            .setSealFingerprint(seal)
                                            .setSealedAt(
                                                    sealTimes.computeIfAbsent(
                                                            d.getRequestId(), k -> new Date()));
                            if ("SEAL".equals(d.getOperation())) {
                                value.setStatus("SEALED").setHeld(true).setApplied(false);
                                if ("SEAL_LOST".equals(mode))
                                    throw new IllegalStateException("sealed response lost");
                            } else {
                                var proof =
                                        QmsInspectionRevisionOutcomeContract.parse(
                                                d.getOutcome(), d.getIntent());
                                value.setResolvedAt(new Date())
                                        .setOutcomeFingerprint(d.getOutcomeFingerprint())
                                        .setHeld(false);
                                if ("APPLY".equals(d.getOperation())) {
                                    value.setStatus("APPLIED")
                                            .setApplied(true)
                                            .setEventId(
                                                    Long.valueOf(
                                                            proof.event()
                                                                    .path("eventId")
                                                                    .textValue()))
                                            .setEventFingerprint(
                                                    QmsInspectionRevisionOutcomeContract.hash(
                                                            proof.eventPayload()));
                                    if ("APPLY_LOST".equals(mode))
                                        throw new IllegalStateException("applied response lost");
                                } else {
                                    value.setStatus("ABORTED").setApplied(false);
                                    var cancel =
                                            new MesInspectionRevisionDeliveryDTO()
                                                    .setOperation("CANCEL")
                                                    .setRequestId(d.getRequestId())
                                                    .setTenantId(1L)
                                                    .setFactoryCode("F1")
                                                    .setIntent(d.getIntent())
                                                    .setIntentFingerprint(d.getIntentFingerprint())
                                                    .setOperatorId(proof.abortBy())
                                                    .setReason(proof.reason())
                                                    .setIssuedAt(
                                                            java.time.Instant.now()
                                                                    .getEpochSecond())
                                                    .setNonce(UUID.randomUUID().toString());
                                    cancel.setSignature(
                                            cancel.sign(
                                                    QmsInspectionRevisionCoordinationTransactionTest
                                                            .KEY));
                                    value.setCancellation(
                                            f.mes.inspectionRevisionAdmission(cancel).getData());
                                }
                            }
                            return R.ok(value);
                        });
    }

    @AfterEach
    void clear() {
        if (f != null) f.clear();
    }

    Long id() {
        return f.request.getId();
    }

    void start() {
        control.start(700L, id());
    }

    void run() {
        engine.advance(id());
    }

    @Test
    void approvedCommandActuallyChangesTaskAndPublishesNewResultExactlyOnce() {
        start();
        run();
        var state = control.state(700L, id());
        assertEquals("APPLIED", state.status());
        assertTrue(state.resultChanged());
        assertFalse(state.canAbort());
        assertEquals(
                "UN_QUALIFIED",
                f.f.f.db.queryForObject(
                        "SELECT result FROM mom_qms.qms_inspection_procedure_task WHERE id=700",
                        String.class));
        assertEquals(
                "UNHANDLED",
                f.f.f.db.queryForObject(
                        "SELECT status FROM mom_qms.qms_inspection_procedure_task WHERE id=700",
                        String.class));
        assertEquals(
                3,
                f.f.f.db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_event", Integer.class));
        run();
        assertEquals(
                3,
                f.f.f.db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_event", Integer.class));
        assertEquals("EXECUTED", f.f.service.detail(700L, id()).state());
    }

    @Test
    void applyUnknownNeverAllowsRollbackOrSecondHistory() {
        start();
        mode = "APPLY_LOST";
        run();
        assertEquals("APPLY", control.state(700L, id()).phase());
        assertTrue(control.state(700L, id()).resultChanged());
        assertThrows(
                RuntimeException.class,
                () ->
                        control.abort(
                                700L, id(), new QmsInspectionRevisionCancelCommand("不能撤销已提交结果")));
        mode = "OK";
        control.retry(700L, id());
        run();
        assertEquals("APPLIED", control.state(700L, id()).status());
        assertEquals(
                3,
                f.f.f.db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_event", Integer.class));
    }

    @Test
    void unknownSealCanBeStoppedWithoutChangingTheOriginalTask() {
        start();
        mode = "SEAL_LOST";
        run();
        control.abort(700L, id(), new QmsInspectionRevisionCancelCommand("中止执行"));
        mode = "OK";
        run();
        assertEquals("ABORTED", control.state(700L, id()).status());
        assertEquals(
                "QUALIFIED",
                f.f.f.db.queryForObject(
                        "SELECT result FROM mom_qms.qms_inspection_procedure_task WHERE id=700",
                        String.class));
        assertEquals(
                2,
                f.f.f.db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_event", Integer.class));
        assertThrows(RuntimeException.class, () -> commit.commit(id()));
    }

    @Test
    void localFailureRollsBackTaskHistoryOutcomeTogetherAndRemainsAbortable() {
        start();
        mode = "LOCAL_FAIL";
        run();
        assertEquals("ATTENTION", control.state(700L, id()).status());
        assertEquals(
                "QUALIFIED",
                f.f.f.db.queryForObject(
                        "SELECT result FROM mom_qms.qms_inspection_procedure_task WHERE id=700",
                        String.class));
        assertEquals(
                2,
                f.f.f.db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_event", Integer.class));
        assertNull(mapper.outcome(id(), 1L, "F1"));
        assertTrue(control.state(700L, id()).canAbort());
    }

    @Test
    void formalRunClosesLegacyPreparationCancelAndRetryEntrances() {
        start();
        assertThrows(
                RuntimeException.class,
                () ->
                        f.service.cancel(
                                700L, id(), new QmsInspectionRevisionCancelCommand("旧入口取消")));
        assertThrows(RuntimeException.class, () -> f.service.retry(700L, id()));
    }

    @Test
    void approvedPersistentCommandCanResumeWithoutForgedLogin() throws Exception {
        start();
        SecurityContextHolder.clearContext();
        run();
        f.f.actor(4);
        assertEquals("APPLIED", control.state(700L, id()).status());
        assertEquals(
                "4",
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(mapper.outcome(id(), 1L, "F1").getPayload())
                        .path("executorId")
                        .textValue());
    }

    @Test
    void canceledPreparationDoesNotOfferFormalExecution() {
        f.prepare();
        f.service.cancel(700L, id(), new QmsInspectionRevisionCancelCommand("取消准备"));
        f.worker.deliver(id());
        assertEquals("PREPARATION_CANCELED", control.state(700L, id()).status());
    }
}
