package com.kitex.mom.qms.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.qms.controller.QmsInspectionEventDeliveryController;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.*;
import com.kitex.mom.qms.service.InspectionProcedureHistoryService;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.authentication.Http403ForbiddenEntryPoint;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.support.GenericWebApplicationContext;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 真实QMS历史→不可变事件→发件事务；仅MES网络端口受控，不冒充跨服务运行验收。 */
public class QmsInspectionEventOutboxTransactionTest {
    static final String KEY = "ordinary-inspection-test-write-secret-at-least32";
    QmsInspectionHistoryTransactionTest origin;
    JdbcTemplate db;
    SqlSessionTemplate session;
    DataSourceTransactionManager tx;
    QmsInspectionEventService journal;
    QmsInspectionEventPublisher publisher;
    QmsInspectionEventWorker worker;
    QmsInspectionEventDispatcher dispatcher;
    QmsInspectionEventDeliveryService delivery;
    QmsInspectionEventOutboxMapper outbox;
    final List<MesInspectionEventDeliveryDTO> sent = new ArrayList<>();
    final Map<Long, MesInspectionEventReceiptDTO> remoteReceipts = new HashMap<>();
    final List<Integer> remoteVisibleQueueCounts = new ArrayList<>();
    final List<String> remoteScopes = new ArrayList<>();
    WriteFault fault = new WriteFault();
    String ackMode = "OK";
    boolean allowRead = true, allowWrite = true;
    CountDownLatch remoteEntered, remoteRelease;
    boolean lockMesBinding;
    CountDownLatch manualHasParent;
    Map<Class<?>, Object> deps;

    @BeforeEach
    void setup() throws Exception {
        origin = new QmsInspectionHistoryTransactionTest();
        origin.setup();
        db = (JdbcTemplate) ReflectionTestUtils.getField(origin, "db");
        session = (SqlSessionTemplate) ReflectionTestUtils.getField(origin, "sessions");
        tx = (DataSourceTransactionManager) ReflectionTestUtils.getField(origin, "transactions");
        deps = (Map<Class<?>, Object>) ReflectionTestUtils.getField(origin, "dependencies");
        var config = session.getConfiguration();
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(config, "ordinary-outbox"),
                QmsInspectionEventOutboxEntity.class);
        config.addMapper(QmsInspectionEventOutboxMapper.class);
        config.addInterceptor(fault);
        db.execute("CREATE TABLE qms_ordinary_outbox_installation(version int)");
        db.update("INSERT INTO qms_ordinary_outbox_installation VALUES(1)");
        db.execute(
                "CREATE ALIAS mom_qms.mom_inspection_event_outbox_ready_v1 FOR"
                    + " 'com.kitex.mom.qms.service.impl.QmsInspectionEventOutboxTransactionTest.ready'");
        db.execute(
                "CREATE TABLE mom_qms.qms_inspection_event_outbox(id bigint PRIMARY KEY,tenant_id"
                    + " bigint,factory_code varchar,task_id bigint,sequence_no bigint,fingerprint"
                    + " varchar,status varchar,attempt_count int,next_attempt_time"
                    + " timestamp,created_at timestamp,confirmed_at timestamp,mes_received_at"
                    + " timestamp,last_error"
                    + " varchar,UNIQUE(tenant_id,factory_code,task_id,sequence_no))");
        db.execute(
                "CREATE TABLE public.qms_inspection_event_outbox AS SELECT * FROM"
                        + " mom_qms.qms_inspection_event_outbox WHERE 1=0");
        outbox = session.getMapper(QmsInspectionEventOutboxMapper.class);
        var mes = mock(RemoteMesTaskService.class);
        when(mes.deliverInspectionEvent(any()))
                .thenAnswer(
                        call -> {
                            MesInspectionEventDeliveryDTO d = call.getArgument(0);
                            assertTrue(d.signatureMatches(KEY));
                            assertEquals(d.getTenantId(), TenantContextHolder.getTenantId());
                            assertEquals(d.getFactoryCode(), FactoryContextHolder.getFactoryCode());
                            assertFalse(TenantContextHolder.getTenantSkip());
                            assertFalse(Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()));
                            var copy = new MesInspectionEventDeliveryDTO();
                            BeanUtils.copyProperties(d, copy);
                            sent.add(copy);
                            remoteScopes.add(
                                    TenantContextHolder.getTenantId()
                                            + ":"
                                            + FactoryContextHolder.getFactoryCode());
                            try (var connection =
                                            Objects.requireNonNull(db.getDataSource())
                                                    .getConnection();
                                    var st = connection.createStatement();
                                    var rs =
                                            st.executeQuery(
                                                    "SELECT COUNT(*) FROM"
                                                        + " mom_qms.qms_inspection_event_outbox")) {
                                rs.next();
                                remoteVisibleQueueCounts.add(rs.getInt(1));
                            }
                            var j = new ObjectMapper().readTree(d.getPayload());
                            var receipt =
                                    remoteReceipts.computeIfAbsent(
                                            d.getEventId(),
                                            id ->
                                                    new MesInspectionEventReceiptDTO()
                                                            .setEventId(id)
                                                            .setTaskId(
                                                                    Long.valueOf(
                                                                            j.path("taskId")
                                                                                    .asText()))
                                                            .setSequenceNo(
                                                                    j.path("sequenceNo")
                                                                            .longValue())
                                                            .setFingerprint(d.getFingerprint())
                                                            .setStatus("RECEIVED")
                                                            .setApplied(false)
                                                            .setReceivedAt(new Date()));
                            var ack = new MesInspectionEventReceiptDTO();
                            BeanUtils.copyProperties(receipt, ack);
                            if (lockMesBinding) {
                                remoteEntered.countDown();
                                try (var c =
                                        Objects.requireNonNull(db.getDataSource())
                                                .getConnection()) {
                                    c.setAutoCommit(false);
                                    try (var statement =
                                            c.prepareStatement(
                                                    "SELECT id FROM mes_binding_probe WHERE id=1"
                                                            + " FOR UPDATE")) {
                                        statement.executeQuery().close();
                                    } finally {
                                        c.rollback();
                                    }
                                }
                            }
                            if (remoteEntered != null && !lockMesBinding) {
                                remoteEntered.countDown();
                                assertTrue(remoteRelease.await(5, TimeUnit.SECONDS));
                            }
                            switch (ackMode) {
                                case "THROW" ->
                                        throw new IllegalStateException("unknown remote receipt");
                                case "NULL_RESPONSE" -> {
                                    return null;
                                }
                                case "EMPTY" -> {
                                    return R.ok();
                                }
                                case "FAILED_DATA" -> {
                                    return R.failed(ack, "not successful");
                                }
                                case "EVENT" -> ack.setEventId(d.getEventId() + 1);
                                case "TASK" -> ack.setTaskId(701L);
                                case "SEQUENCE" -> ack.setSequenceNo(99L);
                                case "SHA" -> ack.setFingerprint("b".repeat(64));
                                case "STATUS" -> ack.setStatus("DELIVERED");
                                case "APPLIED" -> ack.setApplied(true);
                                case "NO_TIME" -> ack.setReceivedAt(null);
                                case "ZERO_TIME" -> ack.setReceivedAt(new Date(0));
                                case "FUTURE_TIME" ->
                                        ack.setReceivedAt(
                                                new Date(System.currentTimeMillis() + 60000));
                                case "NO_APPLIED" -> ack.setApplied(null);
                                default -> {}
                            }
                            return R.ok(ack);
                        });
        worker = proxy(new QmsInspectionEventWorker(outbox, mes));
        ReflectionTestUtils.setField(worker, "deliverySecret", KEY);
        dispatcher = new QmsInspectionEventDispatcher(outbox, worker);
        publisher = new QmsInspectionEventPublisher(outbox);
        journal = (QmsInspectionEventService) deps.get(QmsInspectionEventService.class);
        ReflectionTestUtils.setField(journal, "publisher", publisher);
        var access = (QmsInspectionHistorySupport) deps.get(QmsInspectionHistorySupport.class);
        delivery = proxy(new QmsInspectionEventDeliveryService(journal, access, publisher, outbox));
        var factories =
                (RemoteMdmFactoryAccessService) deps.get(RemoteMdmFactoryAccessService.class);
        doAnswer(
                        c -> {
                            com.kitex.mom.mdm.dto.MdmFactoryAccessCheck d = c.getArgument(0);
                            return R.ok(
                                    d.getUserId().equals(2L)
                                            && "F1".equals(d.getFactoryCode())
                                            && ("read".equals(d.getRequiredPermission())
                                                    ? allowRead
                                                    : "write".equals(d.getRequiredPermission())
                                                            && allowWrite));
                        })
                .when(factories)
                .check(any());
        db.update(
                "UPDATE mom_qms.qms_inspection_procedure_task SET"
                    + " source_task_code='P1',source_request_id='MES_QMS_V2:P1:FIRST:10',source_request_fingerprint=?,mat_code='M1',process_code='OP1',label_type='FIRST',standard_id=9,batch_no='B1',order_no='T1',order_id='100',inspection_num=1",
                "a".repeat(64));
    }

    @AfterEach
    void clear() {
        origin.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    public static Integer ready(java.sql.Connection c) throws Exception {
        try (var s = c.createStatement();
                var r = s.executeQuery("SELECT version FROM qms_ordinary_outbox_installation")) {
            return r.next() ? r.getInt(1) : null;
        }
    }

    @Test
    void originalCommitCreatesTheQueueBeforeAnyRemoteSend() {
        new TransactionTemplate(tx)
                .execute(
                        status -> {
                            writeAndDispatch("CREATE");
                            assertEquals(1, count());
                            assertTrue(sent.isEmpty());
                            return null;
                        });
        assertTrue(sent.isEmpty(), "原请求提交后不进行同步回调");
        assertTrue(worker.deliver(700L));
        assertEquals(1, sent.size());
        assertEquals(List.of(1), remoteVisibleQueueCounts);
        assertEquals("RECEIVED", state());
        assertEquals(1, attempts());
        assertNotNull(value("confirmed_at", Date.class));
        assertNotNull(value("mes_received_at", Date.class));
        assertEquals(
                journal.events("PROCEDURE", 700L).get(0).getPayload(), sent.get(0).getPayload());
    }

    @Test
    void rollbackOfTheOriginalTransactionLeavesNoEventQueueOrRemoteEffect() {
        new TransactionTemplate(tx)
                .execute(
                        status -> {
                            writeAndDispatch("CREATE");
                            assertEquals(1, count());
                            status.setRollbackOnly();
                            return null;
                        });
        assertEquals(0, count());
        assertTrue(sent.isEmpty());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_event", Integer.class));
    }

    @Test
    void queueInsertZeroRollsBackTheActualParentHistoryAndJournal() {
        fault.zeroInsert = true;
        assertThrows(
                RuntimeException.class,
                () ->
                        new TransactionTemplate(tx)
                                .execute(
                                        status -> {
                                            writeAndDispatch("CREATE");
                                            return null;
                                        }));
        assertEquals(0, count());
        assertTrue(sent.isEmpty());
        assertEquals(
                "FINISHED",
                db.queryForObject(
                        "SELECT status FROM mom_qms.qms_inspection_procedure_task WHERE id=700",
                        String.class));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_procedure_history",
                        Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_event", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE", "APPEND", "RESULT", "EXEMPTION", "CANCELED"})
    void everyOriginalOrdinaryActionUsesTheExactImmutablePayload(String action) {
        writeAndDispatch(action);
        assertEquals(1, count());
        assertEquals(1, sent.size());
        assertEquals(action, journal.events("PROCEDURE", 700L).get(0).getEventType());
        assertEquals(
                journal.events("PROCEDURE", 700L).get(0).getPayload(), sent.get(0).getPayload());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "THROW",
                "NULL_RESPONSE",
                "EMPTY",
                "FAILED_DATA",
                "EVENT",
                "TASK",
                "SEQUENCE",
                "SHA",
                "STATUS",
                "APPLIED",
                "NO_TIME",
                "ZERO_TIME",
                "FUTURE_TIME",
                "NO_APPLIED"
            })
    void anUnknownOrWrongAcknowledgementCannotMarkTheOriginalReceived(String mode) {
        ackMode = mode;
        writeAndDispatch("CREATE");
        assertEquals(1, count());
        assertEquals(1, sent.size());
        assertEquals("RETRY_WAIT", state());
        assertEquals(1, attempts());
        assertNull(value("confirmed_at", Date.class));
        assertNull(value("mes_received_at", Date.class));
        assertNotNull(value("last_error", String.class));
    }

    @Test
    void lostAckRestoresTheSameRawEventAndOriginalMesReceipt() {
        ackMode = "THROW";
        writeAndDispatch("CREATE");
        assertEquals("RETRY_WAIT", state());
        var first = sent.get(0);
        Date actual = remoteReceipts.get(first.getEventId()).getReceivedAt();
        ackMode = "OK";
        makeDue();
        assertTrue(worker.deliver(700L));
        assertEquals("RECEIVED", state());
        assertEquals(2, sent.size());
        assertEquals(first.getEventId(), sent.get(1).getEventId());
        assertEquals(first.getPayload(), sent.get(1).getPayload());
        assertEquals(first.getFingerprint(), sent.get(1).getFingerprint());
        assertNotEquals(first.getNonce(), sent.get(1).getNonce());
        assertEquals(actual.getTime(), value("mes_received_at", Date.class).getTime());
    }

    @Test
    void aZeroLocalReceiptLeavesTheCommittedSourceReadyForTheSameEventRetry() {
        fault.zeroUpdate = true;
        writeAndDispatch("CREATE");
        assertEquals(1, count());
        assertEquals("PENDING", state());
        assertEquals(1, sent.size());
        fault.zeroUpdate = false;
        assertTrue(worker.deliver(700L));
        assertEquals(2, sent.size());
        assertEquals(sent.get(0).getPayload(), sent.get(1).getPayload());
        assertEquals("RECEIVED", state());
    }

    @Test
    void aFailedPredecessorBlocksTheLaterEventAndSurvivesNewCapture() {
        ackMode = "THROW";
        writeAndDispatch("CREATE");
        writeAndDispatch("RESULT");
        assertEquals(2, count());
        assertEquals(1, sent.size());
        assertEquals("RETRY_WAIT", state());
        ackMode = "OK";
        makeDue();
        assertTrue(worker.deliver(700L));
        assertEquals(2, sent.size());
        assertTrue(worker.deliver(700L));
        assertEquals(3, sent.size());
        assertNotEquals(sent.get(1).getEventId(), sent.get(2).getEventId());
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_event_outbox WHERE"
                                + " status='RECEIVED'",
                        Integer.class));
    }

    @Test
    void eightPersistedFailuresNeedExplicitRetryWithoutChangingTheOriginal() {
        ackMode = "THROW";
        writeAndDispatch("CREATE");
        for (int i = 1; i < 8; i++) {
            makeDue();
            assertFalse(worker.deliver(700L));
        }
        assertEquals(8, sent.size());
        assertEquals("ATTENTION", state());
        writeAndDispatch("RESULT");
        assertEquals(8, sent.size());
        var id = firstId();
        ackMode = "OK";
        assertTrue(delivery.retry(700L));
        assertTrue(worker.deliver(700L));
        assertEquals("RECEIVED", state());
        assertEquals(9, attempts());
        assertEquals(id, firstId());
        assertEquals(sent.get(0).getPayload(), sent.get(8).getPayload());
    }

    @Test
    void alreadyReceivedOriginalCannotBeDeliveredAgain() {
        writeAndDispatch("CREATE");
        assertEquals(1, sent.size());
        assertFalse(worker.deliver(700L));
        assertEquals(1, sent.size());
        assertEquals(1, attempts());
    }

    @Test
    void aMissingWriteKeyRetainsTheOriginalForOperatorRecovery() {
        ReflectionTestUtils.setField(worker, "deliverySecret", "");
        writeAndDispatch("CREATE");
        assertEquals(1, count());
        assertEquals("ATTENTION", state());
        assertTrue(sent.isEmpty());
        ReflectionTestUtils.setField(worker, "deliverySecret", KEY);
        assertTrue(delivery.retry(700L));
        assertTrue(worker.deliver(700L));
        assertEquals("RECEIVED", state());
    }

    @Test
    void existingGenuineEventsCanBeQueuedWithoutInventingEarlierHistory() {
        var disabled = mock(QmsInspectionEventPublisher.class);
        ReflectionTestUtils.setField(journal, "publisher", disabled);
        writeAndDispatch("CREATE");
        writeAndDispatch("RESULT");
        assertEquals(0, count());
        assertEquals(2, journal.events("PROCEDURE", 700L).size());
        ReflectionTestUtils.setField(journal, "publisher", publisher);
        assertTrue(delivery.retry(700L));
        assertTrue(worker.deliver(700L));
        assertEquals(2, count());
        assertEquals(1, sent.size());
        assertEquals(
                journal.events("PROCEDURE", 700L).get(0).getPayload(), sent.get(0).getPayload());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRODUCT", "MANUAL", "REWORK"})
    void nonOrdinarySourcesStayOnTheirExistingBusinessPath(String source) {
        if ("PRODUCT".equals(source)) {
            ReflectionTestUtils.invokeMethod(origin, "write", "PRODUCT", "CREATE");
        } else {
            db.update(
                    "UPDATE mom_qms.qms_inspection_procedure_task SET source_request_id=?",
                    source.equals("MANUAL") ? "MANUAL-1" : "REWORK-REINSPECTION:R1");
            writeAndDispatch("CREATE");
        }
        assertEquals(0, count());
        assertTrue(sent.isEmpty());
    }

    @Test
    void missingMigrationCannotSilentlyOmitAnOrdinaryEvent() {
        db.update("UPDATE qms_ordinary_outbox_installation SET version=0");
        assertThrows(RuntimeException.class, () -> writeAndDispatch("CREATE"));
        assertEquals(0, count());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_event", Integer.class));
        assertTrue(sent.isEmpty());
    }

    @Test
    void originalCorruptionIsNotReplacedFromTheCurrentTask() {
        ackMode = "THROW";
        writeAndDispatch("CREATE");
        String raw = sent.get(0).getPayload();
        db.update("UPDATE mom_qms.qms_inspection_event SET payload='{}'");
        makeDue();
        assertFalse(worker.deliver(700L));
        assertEquals(1, sent.size());
        assertEquals("ATTENTION", state());
        assertNotEquals(
                raw,
                db.queryForObject(
                        "SELECT payload FROM mom_qms.qms_inspection_event", String.class));
    }

    @Test
    void readAndManualRecoveryRequireTheActualFactoryPermission() {
        ackMode = "THROW";
        writeAndDispatch("CREATE");
        assertEquals(1, delivery.status(700L).size());
        allowRead = false;
        assertThrows(RuntimeException.class, () -> delivery.status(700L));
        allowRead = true;
        allowWrite = false;
        assertThrows(RuntimeException.class, () -> delivery.retry(700L));
        assertEquals("RETRY_WAIT", state());
        SecurityContextHolder.clearContext();
        assertThrows(RuntimeException.class, () -> delivery.retry(700L));
    }

    @Test
    void backgroundScanRestoresItsPriorScopeAndOnlySendsTheOldestOriginal() {
        ackMode = "THROW";
        writeAndDispatch("CREATE");
        ackMode = "OK";
        makeDue();
        SecurityContextHolder.clearContext();
        TenantContextHolder.setTenantId(9L);
        TenantContextHolder.setTenantSkip();
        FactoryContextHolder.setFactoryCode("OLD");
        FactoryContextHolder.setFactorySkip();
        assertEquals(1, dispatcher.scan());
        assertEquals(9L, TenantContextHolder.getTenantId());
        assertTrue(TenantContextHolder.getTenantSkip());
        assertEquals("OLD", FactoryContextHolder.getFactoryCode());
        assertTrue(FactoryContextHolder.getFactorySkip());
        assertEquals(List.of("1:F1", "1:F1"), remoteScopes);
    }

    @Test
    void crossTenantScanCannotBeCalledFromAPersonHttpOrActiveTransaction() {
        assertThrows(RuntimeException.class, () -> dispatcher.scan());
        SecurityContextHolder.clearContext();
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
        assertThrows(RuntimeException.class, () -> dispatcher.scan());
        RequestContextHolder.resetRequestAttributes();
        assertThrows(
                RuntimeException.class,
                () -> new TransactionTemplate(tx).execute(s -> dispatcher.scan()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TENANT", "FACTORY", "SKIP", "FACTORY_SKIP"})
    void directWorkerNeverUsesAForeignOrSkippedOrganization(String bad) {
        ackMode = "THROW";
        writeAndDispatch("CREATE");
        makeDue();
        int before = sent.size();
        switch (bad) {
            case "TENANT" -> TenantContextHolder.setTenantId(2L);
            case "FACTORY" -> FactoryContextHolder.setFactoryCode("F2");
            case "SKIP" -> TenantContextHolder.setTenantSkip();
            case "FACTORY_SKIP" -> FactoryContextHolder.setFactorySkip();
        }
        try {
            worker.deliver(700L);
        } catch (RuntimeException expected) {
        }
        assertEquals(before, sent.size());
    }

    @Test
    void twoConcurrentWorkersCannotSendTheSamePendingEventTwice() throws Exception {
        ackMode = "THROW";
        writeAndDispatch("CREATE");
        ackMode = "OK";
        makeDue();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> scopedDeliver());
            var b = pool.submit(() -> scopedDeliver());
            boolean x = a.get(5, TimeUnit.SECONDS), y = b.get(5, TimeUnit.SECONDS);
            assertNotEquals(x, y);
            assertEquals(2, sent.size());
            assertEquals("RECEIVED", state());
        } finally {
            pool.shutdownNow();
        }
    }

    boolean scopedDeliver() {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        try {
            return worker.deliver(700L);
        } finally {
            TenantContextHolder.clear();
            FactoryContextHolder.clear();
        }
    }

    @Test
    void waitingForMesNeverHoldsTheOriginalQmsTaskRowLock() throws Exception {
        remoteEntered = new CountDownLatch(1);
        remoteRelease = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        var send =
                pool.submit(
                        () -> {
                            TenantContextHolder.setTenantId(1L);
                            FactoryContextHolder.setFactoryCode("F1");
                            try {
                                writeAndDispatch("CREATE");
                                return null;
                            } finally {
                                TenantContextHolder.clear();
                                FactoryContextHolder.clear();
                            }
                        });
        try {
            assertTrue(remoteEntered.await(5, TimeUnit.SECONDS));
            var business =
                    pool.submit(
                            () ->
                                    db.update(
                                            "UPDATE mom_qms.qms_inspection_procedure_task SET"
                                                    + " status='RUNNING' WHERE id=700"));
            assertEquals(1, business.get(2, TimeUnit.SECONDS));
            assertFalse(send.isDone());
            remoteRelease.countDown();
            send.get(5, TimeUnit.SECONDS);
            assertEquals("RECEIVED", state());
        } finally {
            remoteRelease.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void backgroundScanDeliversActualIndependentOrganizationsWithoutGlobalSkip() {
        ackMode = "THROW";
        writeAndDispatch("CREATE");
        TenantContextHolder.setTenantId(2L);
        FactoryContextHolder.setFactoryCode("F2");
        db.update(
                "INSERT INTO"
                    + " mom_qms.qms_inspection_procedure_task(id,tenant_id,factory_code,num,status,source_task_code,source_request_id,source_request_fingerprint,mat_code,process_code,label_type,standard_id,batch_no,order_no,order_id,inspection_num)"
                    + " VALUES(701,2,'F2',5,'WAIT','P2','MES_QMS_V2:P2:FIRST:20',?,'M2','OP2','FIRST',9,'B2','T2','200',1)",
                "a".repeat(64));
        InspectionProcedureHistoryService history =
                (InspectionProcedureHistoryService)
                        ReflectionTestUtils.getField(origin, "procedure");
        var task = session.getMapper(QmsInspectionProcedureTaskMapper.class).selectById(701L);
        history.create(task);
        assertFalse(worker.deliver(701L));
        assertEquals(2, sent.size());
        ackMode = "OK";
        makeDue();
        SecurityContextHolder.clearContext();
        TenantContextHolder.setTenantId(9L);
        TenantContextHolder.setTenantSkip();
        FactoryContextHolder.setFactoryCode("OLD");
        FactoryContextHolder.setFactorySkip();
        assertEquals(2, dispatcher.scan());
        assertEquals(List.of("1:F1", "2:F2", "1:F1", "2:F2"), remoteScopes);
        assertEquals(9L, TenantContextHolder.getTenantId());
        assertEquals("OLD", FactoryContextHolder.getFactoryCode());
        assertTrue(TenantContextHolder.getTenantSkip());
        assertTrue(FactoryContextHolder.getFactorySkip());
    }

    @Test
    void aV2MarkerWithMissingSourceFingerprintCannotOmitTheFailedQueue() {
        db.update(
                "UPDATE mom_qms.qms_inspection_procedure_task SET source_request_fingerprint=NULL");
        assertThrows(RuntimeException.class, () -> writeAndDispatch("CREATE"));
        assertEquals(0, count());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_event", Integer.class));
    }

    @Test
    void actualMvcReadAndRetryRespectDifferentMenuPermissions() throws Exception {
        ackMode = "THROW";
        writeAndDispatch("CREATE");
        int before = sent.size();
        var permissions = new MenuPermissions();
        var web = new GenericWebApplicationContext();
        web.setServletContext(new MockServletContext());
        AnnotationConfigUtils.registerAnnotationConfigProcessors(web);
        web.registerBean(QmsInspectionHistoryTransactionTest.WebConfiguration.class);
        web.registerBean("pms", MenuPermissions.class, () -> permissions);
        web.registerBean(
                QmsInspectionEventDeliveryController.class,
                () -> new QmsInspectionEventDeliveryController(delivery));
        web.refresh();
        try {
            var mvc =
                    MockMvcBuilders.webAppContextSetup(web)
                            .addFilters(
                                    new ExceptionTranslationFilter(
                                            new Http403ForbiddenEntryPoint()))
                            .build();
            String base = "/inspection/procedure/task/history/events/delivery";
            mvc.perform(get(base).param("taskId", "700"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].status").value("RETRY_WAIT"));
            permissions.read = false;
            mvc.perform(get(base).param("taskId", "700")).andExpect(status().isForbidden());
            mvc.perform(post(base + "/retry").param("taskId", "700"))
                    .andExpect(status().isForbidden());
            assertEquals(before, sent.size());
            permissions.write = true;
            allowWrite = false;
            assertThrows(
                    Exception.class,
                    () -> mvc.perform(post(base + "/retry").param("taskId", "700")));
            assertEquals(before, sent.size());
            allowWrite = true;
            ackMode = "OK";
            mvc.perform(post(base + "/retry").param("taskId", "700"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").value(true));
            assertEquals("RETRY_WAIT", state());
            assertEquals(before, sent.size());
            assertTrue(worker.deliver(700L));
            assertEquals("RECEIVED", state());
        } finally {
            web.close();
        }
    }

    public static class MenuPermissions {
        boolean read = true, write;

        public boolean hasPermission(String permission) {
            return permission.equals("procedureTask:history")
                    ? read
                    : permission.equals("procedureTask:handle") && write;
        }
    }

    @Test
    void aQmsBusinessResponseMustNotSynchronouslyCallBackItsMesCaller() {
        ReflectionTestUtils.invokeMethod(origin, "write", "PROCEDURE", "CREATE");
        assertEquals(1, count());
        assertTrue(sent.isEmpty(), "MES撤回持绑定行锁等待QMS响应，原请求不得同步回调MES");
        assertEquals("PENDING", state());
    }

    @Test
    void manualRetryCannotHoldTheQmsParentWhileWaitingForTheSendingAnchor() throws Exception {
        // 三个独立事务角色：MES持绑定等QMS；发送器持锚等MES；人工持QMS任务争用锚。
        ReflectionTestUtils.invokeMethod(origin, "write", "PROCEDURE", "CREATE");
        db.execute("SET DEFAULT_LOCK_TIMEOUT 3000");
        db.execute("CREATE TABLE mes_binding_probe(id bigint PRIMARY KEY)");
        db.update("INSERT INTO mes_binding_probe VALUES(1)");
        lockMesBinding = true;
        remoteEntered = new CountDownLatch(1);
        manualHasParent = new CountDownLatch(1);
        fault.manualHasParent = manualHasParent;
        var mesHasBinding = new CountDownLatch(1);
        var withdrawMayCallQms = new CountDownLatch(1);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        var pool = Executors.newFixedThreadPool(3);
        java.util.concurrent.Future<?> mesWithdraw = null, send = null, manual = null;
        try {
            mesWithdraw =
                    pool.submit(
                            () ->
                                    new TransactionTemplate(tx)
                                            .execute(
                                                    outer -> {
                                                        db.queryForObject(
                                                                "SELECT id FROM mes_binding_probe"
                                                                        + " WHERE id=1 FOR UPDATE",
                                                                Long.class);
                                                        mesHasBinding.countDown();
                                                        try {
                                                            if (!withdrawMayCallQms.await(
                                                                    5, TimeUnit.SECONDS))
                                                                throw new IllegalStateException(
                                                                        "withdraw setup timeout");
                                                        } catch (InterruptedException e) {
                                                            Thread.currentThread().interrupt();
                                                            throw new IllegalStateException(e);
                                                        }
                                                        var qmsHttp = new TransactionTemplate(tx);
                                                        qmsHttp.setPropagationBehavior(
                                                                org.springframework.transaction
                                                                        .TransactionDefinition
                                                                        .PROPAGATION_REQUIRES_NEW);
                                                        qmsHttp.execute(
                                                                inner -> {
                                                                    db.queryForObject(
                                                                            "SELECT id FROM"
                                                                                + " mom_qms.qms_inspection_procedure_task"
                                                                                + " WHERE id=700"
                                                                                + " FOR UPDATE",
                                                                            Long.class);
                                                                    return null;
                                                                });
                                                        return null;
                                                    }));
            assertTrue(mesHasBinding.await(5, TimeUnit.SECONDS));
            send = pool.submit(() -> scopedDeliver());
            assertTrue(remoteEntered.await(5, TimeUnit.SECONDS));
            var manualAttempt =
                    pool.submit(
                            () -> {
                                Thread.currentThread().setName("ordinary-manual-retry");
                                TenantContextHolder.setTenantId(1L);
                                FactoryContextHolder.setFactoryCode("F1");
                                SecurityContextHolder.getContext().setAuthentication(auth);
                                try {
                                    delivery.retry(700L);
                                    return "unexpected success";
                                } catch (RuntimeException busy) {
                                    return busy.getMessage();
                                } finally {
                                    SecurityContextHolder.clearContext();
                                    TenantContextHolder.clear();
                                    FactoryContextHolder.clear();
                                }
                            });
            manual = manualAttempt;
            assertTrue(manualHasParent.await(5, TimeUnit.SECONDS), "人工入口必须真实取得QMS任务锁后参与竞争");
            withdrawMayCallQms.countDown();
            String result =
                    assertDoesNotThrow(
                            () -> manualAttempt.get(1, TimeUnit.SECONDS),
                            "人工入口必须非等待退出并释放QMS任务锁，不能让三方互等");
            assertTrue(result.contains("正在投递"), result);
            mesWithdraw.get(5, TimeUnit.SECONDS);
            assertEquals(true, send.get(5, TimeUnit.SECONDS));
            assertEquals("RECEIVED", state());
        } finally {
            withdrawMayCallQms.countDown();
            for (var f : new java.util.concurrent.Future<?>[] {mesWithdraw, send, manual})
                if (f != null) {
                    try {
                        f.get(5, TimeUnit.SECONDS);
                    } catch (Exception expected) {
                        /* 旧实现RED的锁超时清理，不掩盖主断言。 */
                    }
                }
            pool.shutdownNow();
        }
    }

    void writeAndDispatch(String action) {
        ReflectionTestUtils.invokeMethod(origin, "write", "PROCEDURE", action);
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            dispatcher.dispatch(
                    TenantContextHolder.getTenantId(), FactoryContextHolder.getFactoryCode(), 700L);
    }

    int count() {
        return db.queryForObject(
                "SELECT COUNT(*) FROM mom_qms.qms_inspection_event_outbox", Integer.class);
    }

    Long firstId() {
        return db.queryForObject(
                "SELECT id FROM mom_qms.qms_inspection_event_outbox ORDER BY sequence_no LIMIT 1",
                Long.class);
    }

    String state() {
        return value("status", String.class);
    }

    int attempts() {
        return value("attempt_count", Integer.class);
    }

    <T> T value(String name, Class<T> t) {
        return db.queryForObject(
                "SELECT "
                        + name
                        + " FROM mom_qms.qms_inspection_event_outbox ORDER BY sequence_no LIMIT 1",
                t);
    }

    void makeDue() {
        db.update(
                "UPDATE mom_qms.qms_inspection_event_outbox SET next_attempt_time=TIMESTAMP"
                        + " '2026-01-01 00:00:00' WHERE status IN('PENDING','RETRY_WAIT')");
    }

    <T> T proxy(T target) {
        var p = new ProxyFactory(target);
        p.setProxyTargetClass(true);
        p.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        return (T) p.getProxy();
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
    static class WriteFault implements Interceptor {
        boolean zeroInsert, zeroUpdate;
        CountDownLatch manualHasParent;

        public Object intercept(Invocation i) throws Throwable {
            String id = ((MappedStatement) i.getArgs()[0]).getId();
            if (id.contains("QmsInspectionEventOutboxMapper")
                    && ((zeroInsert && id.endsWith(".insert"))
                            || (zeroUpdate && id.endsWith(".update")))) return 0;
            Object result = i.proceed();
            if (id.endsWith("QmsInspectionEventOutboxMapper.sourceTask")
                    && Thread.currentThread().getName().equals("ordinary-manual-retry")
                    && manualHasParent != null) manualHasParent.countDown();
            return result;
        }
    }
}
