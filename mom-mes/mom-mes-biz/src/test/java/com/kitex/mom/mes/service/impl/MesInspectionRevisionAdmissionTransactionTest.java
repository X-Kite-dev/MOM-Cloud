package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionExecutionContract;
import com.kitex.mom.qms.entity.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.*;

/** 真实V2创建、两件收件/应用及根互斥；QMS签名意图是边界端口，不冒充跨服务执行。 */
public class MesInspectionRevisionAdmissionTransactionTest {
    static final String KEY = "revision-admission-test-only-write-key-at-least32";
    static final Long REQUEST = 9007199254740993L;
    MesInspectionEventApplicationTransactionTest f;
    MesInspectionRevisionAdmissionService service;
    String intent;
    ObjectNode result;

    @BeforeEach
    void setup() throws Exception {
        f = new MesInspectionEventApplicationTransactionTest();
        f.setup();
        f.db.update("DELETE FROM mes_task_report_record");
        f.db.update("DELETE FROM mes_task_report");
        f.db.update("DELETE FROM mes_warehouse_live_stock");
        f.db.update("DELETE FROM mes_quality_stock_proof");
        f.db.update("UPDATE mes_task SET complete_num=0");
        f.db.update(
                "UPDATE mes_produce_task SET complete_num=0,quality_released_num=0,un_pass_num=0");
        f.db.update(
                "UPDATE mes_task_arrange SET complete_num=0,quality_released_num=0,status='PAUSE'");
        f.receive(f.first());
        assertTrue(f.worker.consume(10L));
        result = f.result(f.first(), "QUALIFIED", 5, 5);
        f.receive(result);
        assertTrue(f.worker.consume(10L));
        service =
                f.proxy(
                        new MesInspectionRevisionAdmissionService(
                                f.session.getMapper(MesInspectionRevisionAdmissionMapper.class),
                                f.session.getMapper(MesInspectionRevisionCancelMapper.class),
                                f.mutex,
                                f.holds,
                                f.states,
                                f.inbox,
                                f.projection,
                                f.sources));
        ReflectionTestUtils.setField(service, "deliverySecret", KEY);
        intent = intent();
    }

    @AfterEach
    void clear() {
        if (f != null) f.clear();
    }

    String intent() throws Exception {
        var json = new ObjectMapper();
        var when = new Date(System.currentTimeMillis() - 1000);
        var requested = new Date(when.getTime() - 2000);
        var reviewed = new Date(when.getTime() - 1000);
        var req = json.createObjectNode();
        req.put("schemaVersion", 1)
                .put("requestId", REQUEST.toString())
                .put("tenantId", "1")
                .put("factoryCode", "F1")
                .put("taskId", "700")
                .put("baseEventId", result.path("eventId").textValue())
                .put("baseSequence", 2)
                .put(
                        "baseFingerprint",
                        MesInspectionEventApplicationTransactionTest.hash(
                                json.writeValueAsString(result)))
                .put("requestedBy", "2")
                .put("requestedAt", requested.toInstant().toString())
                .put("targetResult", "UN_QUALIFIED")
                .put("targetUsable", 0)
                .put("targetHandled", 0)
                .put("reason", "量具复核发现原首检误判");
        req.set("source", result.get("source").deepCopy());
        String payload = json.writeValueAsString(req),
                fp = QmsInspectionRevisionExecutionContract.fingerprint(payload);
        var row =
                new QmsInspectionRevisionEntity()
                        .setId(REQUEST)
                        .setTenantId(1L)
                        .setFactoryCode("F1")
                        .setTaskId(700L)
                        .setBaseEventId(Long.valueOf(result.path("eventId").textValue()))
                        .setBaseSequence(2L)
                        .setBaseFingerprint(req.path("baseFingerprint").textValue())
                        .setRequestedBy(2L)
                        .setRequestedAt(requested)
                        .setTargetResult("UN_QUALIFIED")
                        .setTargetUsable(0)
                        .setTargetHandled(0)
                        .setReason(req.path("reason").textValue())
                        .setPayload(payload)
                        .setFingerprint(fp);
        var review =
                new QmsInspectionRevisionReviewEntity()
                        .setId(REQUEST)
                        .setTenantId(1L)
                        .setFactoryCode("F1")
                        .setTaskId(700L)
                        .setRequestFingerprint(fp)
                        .setReviewedBy(3L)
                        .setReviewedAt(reviewed)
                        .setDecision("APPROVED")
                        .setNote("独立复核原检验和量具记录");
        return QmsInspectionRevisionExecutionContract.freeze(row, review, 4L, when);
    }

    MesInspectionRevisionDeliveryDTO command(String op) {
        var d =
                new MesInspectionRevisionDeliveryDTO()
                        .setOperation(op)
                        .setRequestId(REQUEST)
                        .setTenantId(1L)
                        .setFactoryCode("F1")
                        .setIntent(intent)
                        .setIntentFingerprint(
                                QmsInspectionRevisionExecutionContract.fingerprint(intent))
                        .setOperatorId("CANCEL".equals(op) ? 9L : 4L)
                        .setReason("CANCEL".equals(op) ? "上游终止本次执行" : null)
                        .setIssuedAt(Instant.now().getEpochSecond())
                        .setNonce(UUID.randomUUID().toString());
        d.setSignature(d.sign(KEY));
        return d;
    }

    int count(String table) {
        return f.db.queryForObject("SELECT COUNT(*) FROM mom_mes." + table, Integer.class);
    }

    @Test
    void reserveFreezesRealRootWithoutChangingQualityOrCounts() {
        var before = f.db.queryForList("SELECT * FROM mom_mes.mes_inspection_event_state");
        var receipt = service.receive(command("RESERVE"));
        assertEquals("RESERVED", receipt.getStatus());
        assertTrue(receipt.isHeld());
        assertFalse(receipt.isApplied());
        assertEquals("T1", receipt.getTaskCode());
        assertEquals(10L, receipt.getGenRecordId());
        assertEquals(before, f.db.queryForList("SELECT * FROM mom_mes.mes_inspection_event_state"));
        assertEquals(0, count("mes_task_report"));
        assertEquals(1, count("mes_inspection_revision_admission"));
    }

    @Test
    void duplicateIntentReturnsPermanentReceiptWithoutAnotherHold() {
        var first = service.receive(command("RESERVE"));
        var second = service.receive(command("RESERVE"));
        assertEquals(first.getAdmissionFingerprint(), second.getAdmissionFingerprint());
        assertEquals(first.getReservedAt(), second.getReservedAt());
        assertEquals(1, count("mes_inspection_revision_admission"));
    }

    @Test
    void heldRootBlocksActualInternalQuantityLockAndQualityProjection() {
        service.receive(command("RESERVE"));
        assertThrows(
                RuntimeException.class,
                () ->
                        f.quantities.reconcileEvent(
                                "P1",
                                10L,
                                Long.valueOf(result.path("eventId").textValue()),
                                new Date()));
        var record = f.states.generation(10L, 1L, "F1");
        var resolution = f.projection.resolve(record, true, null, null, null);
        assertNull(resolution.task());
        assertEquals("REVISION_HELD", resolution.status());
        assertEquals(2L, f.sequence());
    }

    @Test
    void cancellationIsAnImmutableReleaseNotAQualityRevision() {
        service.receive(command("RESERVE"));
        var canceled = service.receive(command("CANCEL"));
        assertEquals("CANCELED", canceled.getStatus());
        assertFalse(canceled.isHeld());
        assertEquals(9L, canceled.getCanceledBy());
        assertEquals(1, count("mes_inspection_revision_admission"));
        assertEquals(1, count("mes_inspection_revision_cancel"));
        assertEquals("ACTIVE", f.state());
        assertEquals(2L, f.sequence());
        assertDoesNotThrow(
                () -> f.quantities.reconcile("P1", new MesQualityGateDecision().setPassed(true)));
    }

    @Test
    void cancelFirstIsPermanentAndCannotInventAdmission() {
        var cancel = service.receive(command("CANCEL"));
        assertNull(cancel.getReservedAt());
        assertNull(cancel.getAdmissionFingerprint());
        assertEquals("CANCELED", service.receive(command("RESERVE")).getStatus());
        assertEquals(0, count("mes_inspection_revision_admission"));
        assertEquals(1, count("mes_inspection_revision_cancel"));
    }

    @Test
    void statusDoesNotCreateEitherBusinessFact() {
        var receipt = service.receive(command("STATUS"));
        assertEquals("ABSENT", receipt.getStatus());
        assertFalse(receipt.isHeld());
        assertEquals(0, count("mes_inspection_revision_admission"));
        assertEquals(0, count("mes_inspection_revision_cancel"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SIGNATURE",
                "ORDINARY_KEY",
                "EXPIRED",
                "FUTURE",
                "NONCE",
                "FACTORY",
                "TENANT",
                "ACTION",
                "RAW",
                "EXECUTOR"
            })
    void unsignedOrMismatchedTransportCannotWrite(String bad) {
        var d = command("RESERVE");
        switch (bad) {
            case "SIGNATURE" -> d.setSignature("a".repeat(64));
            case "ORDINARY_KEY" ->
                    d.setSignature(d.sign(MesInspectionEventApplicationTransactionTest.KEY));
            case "EXPIRED" -> d.setIssuedAt(Instant.now().minusSeconds(100).getEpochSecond());
            case "FUTURE" -> d.setIssuedAt(Instant.now().plusSeconds(30).getEpochSecond());
            case "NONCE" -> d.setNonce("x");
            case "FACTORY" -> d.setFactoryCode("OTHER");
            case "TENANT" -> d.setTenantId(2L);
            case "ACTION" -> d.setOperation("APPLY");
            case "RAW" -> d.setIntent(intent + " ");
            case "EXECUTOR" -> d.setOperatorId(99L);
            default -> fail(bad);
        }
        if (!Set.of("SIGNATURE", "ORDINARY_KEY").contains(bad)) d.setSignature(d.sign(KEY));
        assertThrows(RuntimeException.class, () -> service.receive(d));
        assertEquals(0, count("mes_inspection_revision_admission"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "REPORT",
                "REVERSED_REPORT",
                "ORPHAN_REPORT",
                "PROCESS_NULL",
                "PROCESS_RELEASE",
                "PROCESS_COMPLETE",
                "PROCESS_REJECTED",
                "ARRANGE_NULL",
                "STOCK_PROOF",
                "CLOSED",
                "REWORK",
                "PENDING_EVENT",
                "CONFLICT",
                "SOURCE_SHA",
                "WORKING_ARRANGE",
                "RUNNING_TIME",
                "OPEN_RUN",
                "FIRST_APPLICATION"
            })
    void unsafeSourceNeverGetsAHold(String bad) throws Exception {
        switch (bad) {
            case "REPORT", "REVERSED_REPORT" ->
                    f.db.update(
                            "INSERT INTO"
                                + " mes_task_report(id,tenant_id,factory_code,task_code,produce_task_code,status)"
                                + " VALUES(999,1,'F1','T1','P1',?)",
                            "REPORT".equals(bad) ? "EFFECTIVE" : "REVERSED");
            case "ORPHAN_REPORT" ->
                    f.db.update(
                            "INSERT INTO"
                                + " mes_task_report(id,tenant_id,factory_code,task_code,produce_task_code,status)"
                                + " VALUES(998,1,'F1',NULL,'P1','EFFECTIVE')");
            case "PROCESS_NULL" -> f.db.update("UPDATE mes_produce_task SET complete_num=NULL");
            case "PROCESS_RELEASE" ->
                    f.db.update("UPDATE mes_produce_task SET quality_released_num=1");
            case "PROCESS_COMPLETE" -> f.db.update("UPDATE mes_produce_task SET complete_num=1");
            case "PROCESS_REJECTED" -> f.db.update("UPDATE mes_produce_task SET un_pass_num=1");
            case "ARRANGE_NULL" -> f.db.update("UPDATE mes_task_arrange SET complete_num=NULL");
            case "STOCK_PROOF" ->
                    f.db.update(
                            "INSERT INTO"
                                + " mes_quality_stock_proof(id,tenant_id,factory_code,produce_task_code)"
                                + " VALUES(999,1,'F1','P1')");
            case "CLOSED" -> f.db.update("UPDATE mes_task SET status='CLOSE'");
            case "REWORK" -> f.db.update("UPDATE mes_task SET resource_type='REWORK'");
            case "PENDING_EVENT" -> f.receive(f.result(result, "QUALIFIED", 5, 5));
            case "CONFLICT" ->
                    f.db.update(
                            "UPDATE mom_mes.mes_inspection_event_state SET status='CONFLICT' WHERE"
                                    + " id=10");
            case "FIRST_APPLICATION" ->
                    f.db.update(
                            "UPDATE mom_mes.mes_inspection_event_application SET"
                                + " decision_snapshot=decision_snapshot||' ' WHERE gen_record_id=10"
                                + " AND sequence_no=1");
            case "WORKING_ARRANGE" -> f.db.update("UPDATE mes_task_arrange SET status='PRODUCT'");
            case "RUNNING_TIME" ->
                    f.db.update(
                            "INSERT INTO"
                                + " mom_mes.mes_workpiece_time_session(id,tenant_id,factory_code,task_code,arrange_id,status)"
                                + " VALUES(999,1,'F1','T1',11,'RUNNING')");
            case "OPEN_RUN" ->
                    f.db.update(
                            "INSERT INTO"
                                + " mom_mes.mes_workpiece_time_interval(id,tenant_id,factory_code,task_code,arrange_id,status,kind,start_time)"
                                + " VALUES(999,1,'F1','T1',11,'OPEN','RUN',CURRENT_TIMESTAMP)");
            case "SOURCE_SHA" ->
                    f.db.update(
                            "UPDATE mom_mes.mes_inspection_event_inbox SET payload=payload||' '"
                                    + " WHERE id=?",
                            Long.valueOf(result.path("eventId").textValue()));
            default -> fail(bad);
        }
        assertThrows(RuntimeException.class, () -> service.receive(command("RESERVE")));
        assertEquals(0, count("mes_inspection_revision_admission"));
    }

    @Test
    void aLateOrdinaryVerdictDoesNotGetAppliedWhileHeldAndCancelCannotClearItsConflict()
            throws Exception {
        service.receive(command("RESERVE"));
        f.receive(f.result(result, "UN_QUALIFIED", 0, 0));
        assertThrows(RuntimeException.class, () -> f.worker.consume(10L));
        assertEquals("ACTIVE", f.state());
        service.receive(command("CANCEL"));
        assertFalse(f.worker.consume(10L));
        assertEquals("CONFLICT", f.state());
    }

    @Test
    void changedCancellationCannotOverwriteTheOriginalRelease() {
        service.receive(command("CANCEL"));
        var repeat = command("CANCEL");
        assertEquals(
                service.receive(repeat).getCanceledAt(),
                service.receive(command("CANCEL")).getCanceledAt());
        repeat.setReason("different");
        repeat.setSignature(repeat.sign(KEY));
        assertThrows(RuntimeException.class, () -> service.receive(repeat));
        assertEquals(1, count("mes_inspection_revision_cancel"));
    }

    @Test
    void personnelQuantityPathUsesRealFactoryAuthorizationAndSameHoldGate() {
        var factories =
                org.mockito.Mockito.mock(
                        com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService.class);
        org.mockito.Mockito.when(factories.check(org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.kitex.common.core.util.R.ok(true));
        var staff =
                f.proxy(
                        new MesIdentifierBindingGroupServiceImpl(
                                new MesActualFactoryAccessService(factories),
                                f.session.getMapper(MesTaskMapper.class),
                                f.session.getMapper(MesProduceTaskMapper.class),
                                org.mockito.Mockito.mock(MesIdentifierNodePolicyValidator.class),
                                f.mutex,
                                f.holds));
        ReflectionTestUtils.setField(f.quantities, "bindingGroups", staff);
        f.authenticate();
        service.receive(command("RESERVE"));
        assertThrows(
                RuntimeException.class,
                () -> f.quantities.reconcile("P1", new MesQualityGateDecision().setPassed(true)));
        service.receive(command("CANCEL"));
        assertDoesNotThrow(
                () -> f.quantities.reconcile("P1", new MesQualityGateDecision().setPassed(true)));
        org.mockito.Mockito.verify(factories, org.mockito.Mockito.atLeastOnce())
                .check(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void oldDeliverySignatureCannotAuthorizeThisDomainEvenWithTheSameSecret() {
        var d = command("RESERVE");
        var old =
                new MesInspectionEventDeliveryDTO()
                        .setEventId(d.getRequestId())
                        .setTenantId(d.getTenantId())
                        .setFactoryCode(d.getFactoryCode())
                        .setPayload(d.getIntent())
                        .setFingerprint(d.getIntentFingerprint())
                        .setIssuedAt(d.getIssuedAt())
                        .setNonce(d.getNonce());
        d.setSignature(old.sign(KEY));
        assertThrows(RuntimeException.class, () -> service.receive(d));
    }

    @Test
    void repeatableReadCannotUseAStaleSnapshotForProductionWrites() {
        service.receive(command("CANCEL"));
        var transaction = new org.springframework.transaction.support.TransactionTemplate(f.tx);
        transaction.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThrows(
                RuntimeException.class,
                () ->
                        transaction.execute(
                                s -> {
                                    f.productionLock.lock("T1");
                                    return null;
                                }));
    }

    @Test
    void unchangedIntentCannotBeReboundToAnotherExecutor() throws Exception {
        service.receive(command("RESERVE"));
        var json = new ObjectMapper();
        var raw = (ObjectNode) json.readTree(intent);
        raw.put("executorId", "5");
        var d = command("RESERVE").setOperatorId(5L).setIntent(json.writeValueAsString(raw));
        d.setIntentFingerprint(QmsInspectionRevisionExecutionContract.fingerprint(d.getIntent()));
        d.setSignature(d.sign(KEY));
        assertThrows(RuntimeException.class, () -> service.receive(d));
        assertEquals(1, count("mes_inspection_revision_admission"));
        d.setOperation("CANCEL").setOperatorId(9L).setReason("changed intent");
        d.setSignature(d.sign(KEY));
        assertThrows(RuntimeException.class, () -> service.receive(d));
        assertTrue(f.holds.held("T1"));
    }

    @Test
    void falseZeroFromMissingAdmissionSchemaIsNeverAllowed() {
        f.db.update("UPDATE revision_admission_installation SET version=0");
        assertThrows(RuntimeException.class, () -> service.receive(command("RESERVE")));
        var tx = new org.springframework.transaction.support.TransactionTemplate(f.tx);
        assertThrows(
                RuntimeException.class,
                () ->
                        tx.execute(
                                s -> {
                                    f.productionLock.lock("T1");
                                    return null;
                                }));
    }

    @Test
    void workerNeedsNoInventedPrincipalOrRemoteQmsLookup() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        int calls = f.remoteCalls;
        assertEquals("RESERVED", service.receive(command("RESERVE")).getStatus());
        assertNull(
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication());
        assertEquals(calls, f.remoteCalls);
    }

    @Test
    void actualInternalMvcReturnsStringIdsAndExplicitUnappliedReceipt() throws Exception {
        var web = new org.springframework.web.context.support.GenericWebApplicationContext();
        web.setServletContext(new org.springframework.mock.web.MockServletContext());
        org.springframework.context.annotation.AnnotationConfigUtils
                .registerAnnotationConfigProcessors(web);
        web.registerBean(
                MesInspectionEventApplicationTransactionTest.ApplicationWebConfiguration.class);
        web.registerBean(
                com.kitex.mom.mes.controller.MesInspectionRevisionAdmissionController.class,
                () ->
                        new com.kitex.mom.mes.controller.MesInspectionRevisionAdmissionController(
                                service));
        web.refresh();
        try {
            var mvc =
                    org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(
                                    web)
                            .build();
            var body = new ObjectMapper().writeValueAsString(command("RESERVE"));
            var response =
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.post(
                                                    "/mesTask/internal/inspection-revision-admission")
                                            .contentType("application/json")
                                            .content(body))
                            .andExpect(
                                    org.springframework.test.web.servlet.result
                                            .MockMvcResultMatchers.status()
                                            .isOk())
                            .andReturn();
            var raw = new ObjectMapper().readTree(response.getResponse().getContentAsString());
            assertTrue(raw.path("data").path("requestId").isTextual());
            assertEquals(REQUEST.toString(), raw.path("data").path("requestId").textValue());
            assertFalse(raw.path("data").path("applied").booleanValue());
            assertTrue(raw.path("data").path("reservedAt").textValue().endsWith("Z"));
        } finally {
            web.close();
        }
    }

    @Test
    void insertZeroAfterActualInsertRollsBackTheFreeze() {
        var fault = new AdmissionInsertFault();
        f.session.getConfiguration().addInterceptor(fault);
        fault.table = "MesInspectionRevisionAdmissionMapper";
        assertThrows(RuntimeException.class, () -> service.receive(command("RESERVE")));
        assertEquals(0, count("mes_inspection_revision_admission"));
        assertFalse(f.holds.held("T1"));
    }

    @Test
    void insertZeroAfterActualCancelInsertDoesNotReleaseAnExistingHold() {
        service.receive(command("RESERVE"));
        var fault = new AdmissionInsertFault();
        f.session.getConfiguration().addInterceptor(fault);
        fault.table = "MesInspectionRevisionCancelMapper";
        assertThrows(RuntimeException.class, () -> service.receive(command("CANCEL")));
        assertEquals(0, count("mes_inspection_revision_cancel"));
        assertTrue(f.holds.held("T1"));
    }

    @Test
    void reserveAndAReportWriterSerializeAtTheRealProductionRoot() throws Exception {
        var fault = new AdmissionInsertFault();
        fault.pause = true;
        f.session.getConfiguration().addInterceptor(fault);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var d = command("RESERVE");
        try {
            var reserve = pool.submit(() -> scoped(() -> service.receive(d)));
            assertTrue(fault.entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var write =
                    pool.submit(
                            () ->
                                    scoped(
                                            () ->
                                                    new org.springframework.transaction.support
                                                                    .TransactionTemplate(f.tx)
                                                            .execute(
                                                                    status -> {
                                                                        f.productionLock.lock("T1");
                                                                        f.db.update(
                                                                                "INSERT INTO"
                                                                                    + " mes_task_report(id,tenant_id,factory_code,task_code,produce_task_code,status)"
                                                                                    + " VALUES(999,1,'F1','T1','P1','EFFECTIVE')");
                                                                        return true;
                                                                    })));
            Thread.sleep(100);
            assertFalse(write.isDone());
            fault.release.countDown();
            assertEquals(
                    "RESERVED", reserve.get(5, java.util.concurrent.TimeUnit.SECONDS).getStatus());
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> write.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, count("mes_task_report"));
        } finally {
            fault.release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void reportCommittedFirstPreventsLaterReservation() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var d = command("RESERVE");
        try {
            var write =
                    pool.submit(
                            () ->
                                    scoped(
                                            () ->
                                                    new org.springframework.transaction.support
                                                                    .TransactionTemplate(f.tx)
                                                            .execute(
                                                                    status -> {
                                                                        f.productionLock.lock("T1");
                                                                        f.db.update(
                                                                                "INSERT INTO"
                                                                                    + " mes_task_report(id,tenant_id,factory_code,task_code,produce_task_code,status)"
                                                                                    + " VALUES(999,1,'F1','T1','P1','EFFECTIVE')");
                                                                        entered.countDown();
                                                                        try {
                                                                            assertTrue(
                                                                                    release.await(
                                                                                            5,
                                                                                            java
                                                                                                    .util
                                                                                                    .concurrent
                                                                                                    .TimeUnit
                                                                                                    .SECONDS));
                                                                        } catch (
                                                                                InterruptedException
                                                                                        e) {
                                                                            throw new RuntimeException(
                                                                                    e);
                                                                        }
                                                                        return true;
                                                                    })));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var reserve = pool.submit(() -> scoped(() -> service.receive(d)));
            Thread.sleep(100);
            assertFalse(reserve.isDone());
            release.countDown();
            assertTrue(write.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> reserve.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, count("mes_inspection_revision_admission"));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    <T> T scoped(java.util.concurrent.Callable<T> work) throws Exception {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        try {
            return work.call();
        } finally {
            TenantContextHolder.clear();
            FactoryContextHolder.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @org.apache.ibatis.plugin.Intercepts(
            @org.apache.ibatis.plugin.Signature(
                    type = org.apache.ibatis.executor.Executor.class,
                    method = "update",
                    args = {org.apache.ibatis.mapping.MappedStatement.class, Object.class}))
    static class AdmissionInsertFault implements org.apache.ibatis.plugin.Interceptor {
        String table = "";
        boolean pause;
        final java.util.concurrent.CountDownLatch
                entered = new java.util.concurrent.CountDownLatch(1),
                release = new java.util.concurrent.CountDownLatch(1);

        public Object intercept(org.apache.ibatis.plugin.Invocation call) throws Throwable {
            var id = ((org.apache.ibatis.mapping.MappedStatement) call.getArgs()[0]).getId();
            if (pause && id.endsWith("MesInspectionRevisionAdmissionMapper.insert")) {
                entered.countDown();
                assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
            }
            var result = call.proceed();
            return !table.isEmpty() && id.endsWith(table + ".insert") ? 0 : result;
        }
    }
}
