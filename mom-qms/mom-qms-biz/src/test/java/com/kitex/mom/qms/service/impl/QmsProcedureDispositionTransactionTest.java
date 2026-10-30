package com.kitex.mom.qms.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.*;
import com.kitex.mom.qms.dto.resp.HandleFinishDTO;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.*;
import com.kitex.mom.qms.service.*;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

class QmsProcedureDispositionTransactionTest {
    QmsInspectionRevisionTransactionTest f;
    QmsProcedureDispositionService service;
    QmsProcedureDispositionMapper mapper;
    QmsPlanEventOutboxService plan;
    final Fault fault = new Fault();

    @BeforeEach
    void setup() throws Exception {
        f = new QmsInspectionRevisionTransactionTest();
        f.setup(false);
        var db = f.f.db;
        db.execute(
                "CREATE TABLE IF NOT EXISTS mom_qms.qms_bad_info(id bigint PRIMARY KEY,tenant_id"
                        + " bigint,factory_code varchar,resource_task_id bigint,resource_type"
                        + " varchar,del_flag varchar)");
        db.execute(
                "CREATE ALIAS mom_qms.mom_procedure_disposition_ready_v1 AS 'Integer ready(){return"
                        + " 1;}'");
        db.execute(
                "CREATE TABLE mom_qms.qms_procedure_disposition_receipt(id bigint PRIMARY"
                    + " KEY,tenant_id bigint,factory_code varchar,task_id bigint,request_id"
                    + " varchar,handle_record_id bigint,history_id bigint,event_id bigint,payload"
                    + " text,fingerprint varchar,created_at"
                    + " timestamp,UNIQUE(tenant_id,factory_code,request_id))");
        var c = f.f.session.getConfiguration();
        ReflectionTestUtils.invokeMethod(
                f.f.origin, "table", c, QmsInspectionProcedureHandleRecordEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(c, "disposition"),
                QmsProcedureDispositionReceiptEntity.class);
        c.addMapper(QmsProcedureDispositionMapper.class);
        c.addInterceptor(fault);
        mapper = f.f.session.getMapper(QmsProcedureDispositionMapper.class);
        plan = mock(QmsPlanEventOutboxService.class);
        when(plan.enqueueDisposition(anyString(), anyLong(), anyLong()))
                .thenReturn(new QmsPlanEventOutboxEntity().setEventId("QMS-RELEASE:700"));
        service =
                f.f.proxy(
                        new QmsProcedureDispositionService(
                                mapper,
                                f.f.session.getMapper(QmsInspectionEventMapper.class),
                                f.f.journal,
                                plan,
                                (QmsInspectionHistorySupport)
                                        f.f.deps.get(QmsInspectionHistorySupport.class)));
    }

    @AfterEach
    void clear() {
        if (f != null) f.clear();
    }

    HandleFinishDTO command(String kind, int quantity) {
        var b = service.basis(700L);
        return new HandleFinishDTO()
                .setId(700L)
                .setRequestId(UUID.randomUUID().toString())
                .setHandleResult(kind)
                .setHandleNum(quantity)
                .setHandleRemark("质量确认按批准量继续生产")
                .setExpectedEventId(Long.valueOf(b.eventId()))
                .setExpectedFingerprint(b.fingerprint())
                .setAuthorizationRef("AUTH-01")
                .setConcessionUnrestricted(true);
    }

    int value(String column) {
        return f.f.db.queryForObject(
                "SELECT " + column + " FROM mom_qms.qms_inspection_procedure_task WHERE id=700",
                Integer.class);
    }

    int receipts() {
        return f.f.db.queryForObject(
                "SELECT COUNT(*) FROM mom_qms.qms_procedure_disposition_receipt", Integer.class);
    }

    @Test
    void actualPartialReleaseThenConcessionPersistsHistoryEventAndReliableDelivery() {
        assertTrue(service.handleIfManaged(command("RELEASE", 2)));
        assertEquals(2, value("usable_num"));
        assertEquals(2, value("handle_num"));
        assertEquals(
                "UN_QUALIFIED",
                f.f.db.queryForObject(
                        "SELECT result FROM mom_qms.qms_inspection_procedure_task WHERE id=700",
                        String.class));
        var next = command("CONCESSION", 3);
        assertTrue(service.handleIfManaged(next));
        assertEquals(
                "RECEIVE",
                f.f.db.queryForObject(
                        "SELECT result FROM mom_qms.qms_inspection_procedure_task WHERE id=700",
                        String.class));
        assertEquals(5, value("usable_num"));
        assertEquals(2, receipts());
        assertEquals(4, f.f.journal.originals("PROCEDURE", 700L).size());
        assertEquals(
                4,
                f.f.db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_event_outbox", Integer.class));
        assertEquals(
                2,
                f.f.db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_procedure_history WHERE"
                                + " type='DISPOSITION'",
                        Integer.class));
        verify(plan, times(2)).enqueueDisposition(eq("PROCEDURE"), eq(700L), anyLong());
    }

    @Test
    void replayAfterUnknownCommitsOnceButChangedReasonOrActorRejects() {
        var c = command("RELEASE", 2);
        assertTrue(service.handleIfManaged(c));
        assertTrue(service.handleIfManaged(c));
        assertEquals(1, receipts());
        assertEquals(2, value("usable_num"));
        c.setHandleRemark("换了原因");
        assertThrows(RuntimeException.class, () -> service.handleIfManaged(c));
        c.setHandleRemark("质量确认按批准量继续生产");
        f.actor(3);
        assertThrows(RuntimeException.class, () -> service.handleIfManaged(c));
        assertEquals(1, receipts());
    }

    @Test
    void staleExpectedEventAndUnauthorizedConcessionDoNotChangeQuantities() {
        var old = command("RELEASE", 1);
        service.handleIfManaged(command("RELEASE", 2));
        assertThrows(RuntimeException.class, () -> service.handleIfManaged(old));
        assertThrows(
                RuntimeException.class,
                () -> service.handleIfManaged(command("CONCESSION", 1).setAuthorizationRef(null)));
        assertEquals(1, receipts());
        assertEquals(2, value("handle_num"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"insertRecord", "insertHistory", "insert", "updateTask"})
    void zeroWritesRollbackTaskRecordHistoryEventAndReceipt(String operation) {
        var c = command("RELEASE", 2);
        fault.operation = operation;
        assertThrows(RuntimeException.class, () -> service.handleIfManaged(c));
        assertEquals(0, value("usable_num"));
        assertEquals(0, receipts());
        assertEquals(2, f.f.journal.originals("PROCEDURE", 700L).size());
        assertEquals(
                0,
                f.f.db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_procedure_handle_record",
                        Integer.class));
    }

    @Test
    void factoryWriteAuthorizationIsCheckedBeforeAnyParentLock() {
        var c = command("RELEASE", 2);
        fault.parentLocks = 0;
        f.allowed = false;
        assertThrows(RuntimeException.class, () -> service.handleIfManaged(c));
        assertEquals(0, fault.parentLocks);
        assertEquals(0, receipts());
    }

    @Test
    void planEnqueueFailureRollsBackTheWholeDisposition() {
        var c = command("RELEASE", 2);
        when(plan.enqueueDisposition(anyString(), anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("PLAN local failure"));
        assertThrows(RuntimeException.class, () -> service.handleIfManaged(c));
        assertEquals(0, value("usable_num"));
        assertEquals(0, receipts());
        assertEquals(2, f.f.journal.originals("PROCEDURE", 700L).size());
    }

    @Test
    void existingPublicHandleCommandUsesTheManagedServiceInsteadOfTheLegacyWriter()
            throws Exception {
        var ctor = InspectionProcedureTaskServiceImpl.class.getDeclaredConstructors()[0];
        Object[] args =
                Arrays.stream(ctor.getParameterTypes())
                        .map(t -> t == QmsProcedureDispositionService.class ? service : mock(t))
                        .toArray();
        var entry = (InspectionProcedureTaskService) f.f.proxy(ctor.newInstance(args));
        entry.handleFinish(command("RELEASE", 2));
        assertEquals(2, value("usable_num"));
        assertEquals(1, receipts());
    }

    @Test
    void absentRequestCanBeSupersededOnlyWhenItsImmutableExpectedHeadIsStale() {
        var absent = command("RELEASE", 1);
        var before =
                service.lookup(
                        700L,
                        absent.getRequestId(),
                        absent.getExpectedEventId(),
                        absent.getExpectedFingerprint());
        assertFalse(before.exists());
        assertFalse(before.stale());
        service.handleIfManaged(command("RELEASE", 2));
        var after =
                service.lookup(
                        700L,
                        absent.getRequestId(),
                        absent.getExpectedEventId(),
                        absent.getExpectedFingerprint());
        assertFalse(after.exists());
        assertTrue(after.stale());
        assertEquals(absent.getExpectedEventId().toString(), after.expectedEventId());
        assertThrows(RuntimeException.class, () -> service.handleIfManaged(absent));
        assertEquals(2, value("usable_num"));
    }

    @Test
    void linkedNcrMustUseItsIndependentDispositionExecution() {
        f.f.db.update("INSERT INTO mom_qms.qms_bad_info VALUES(1,1,'F1',700,'PROCEDURE','0')");
        assertFalse(service.basis(700L).eligible());
        var event = f.f.journal.originals("PROCEDURE", 700L).get(1);
        var c =
                new HandleFinishDTO()
                        .setId(700L)
                        .setRequestId(UUID.randomUUID().toString())
                        .setHandleNum(1)
                        .setHandleResult("RELEASE")
                        .setHandleRemark("不能跳过案件")
                        .setExpectedEventId(event.getId())
                        .setExpectedFingerprint(event.getFingerprint());
        assertThrows(RuntimeException.class, () -> service.handleIfManaged(c));
        assertEquals(0, receipts());
        assertEquals(0, value("usable_num"));
    }

    @Intercepts({
        @Signature(
                type = Executor.class,
                method = "update",
                args = {MappedStatement.class, Object.class}),
        @Signature(
                type = Executor.class,
                method = "query",
                args = {
                    MappedStatement.class,
                    Object.class,
                    org.apache.ibatis.session.RowBounds.class,
                    org.apache.ibatis.session.ResultHandler.class
                })
    })
    static class Fault implements Interceptor {
        String operation;
        int parentLocks;

        public Object intercept(Invocation i) throws Throwable {
            String id = ((MappedStatement) i.getArgs()[0]).getId();
            if (id.endsWith("QmsProcedureDispositionMapper.parentLocked")) parentLocks++;
            if (id.contains("QmsProcedureDispositionMapper")
                    && operation != null
                    && id.endsWith("." + operation)) return 0;
            return i.proceed();
        }
    }
}
