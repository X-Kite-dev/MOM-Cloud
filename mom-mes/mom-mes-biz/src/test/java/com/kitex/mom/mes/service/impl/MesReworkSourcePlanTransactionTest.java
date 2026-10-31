package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.MesSourceQuantityKind;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.*;
import com.kitex.mom.oms.dto.OmsOrderLifeCycleDTO;
import com.kitex.mom.plan.feign.RemotePlnIssueService;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Actual MES sender, source ledger, allocation and PLAN originals; only PLAN/QMS are external
 * ports.
 */
public class MesReworkSourcePlanTransactionTest {
    MesReworkCompletionTransactionTest fixture;
    JdbcTemplate db;
    MesApsSourceQuantityService source;
    MesSourceQuantityPlanOutbox plans;
    RemotePlnIssueService remote;
    Object newMapper;
    final Fault fault = new Fault();
    final AtomicInteger calls = new AtomicInteger();
    final ObjectMapper json = new ObjectMapper();
    final Map<String, String> accepted = new ConcurrentHashMap<>();
    final List<String> sent = Collections.synchronizedList(new ArrayList<>());
    String mode = "OK";
    static final String REQUEST = "REWORK-COMPLETED:QMS-RW-PROCEDURE-80";

    public static int ready() {
        return 1;
    }

    @BeforeEach
    void setup() throws Exception {
        fixture = new MesReworkCompletionTransactionTest();
        fixture.setup();
        db = fixture.db;
        var c = (MybatisConfiguration) fixture.sessions.getConfiguration();
        for (var type :
                List.of(
                        MesApsBatchAllocationEntity.class,
                        MesApsSourceQuantityEventEntity.class,
                        MesSourceQuantityPlanOutboxEntity.class))
            ReflectionTestUtils.invokeMethod(fixture, "table", c, type);
        c.addMapper(MesApsBatchAllocationMapper.class);
        c.addMapper(MesApsSourceQuantityEventMapper.class);
        c.addMapper(MesSourceQuantityPlanOutboxMapper.class);
        c.addInterceptor(fault);
        var tenant = new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
        tenant.addInnerInterceptor(
                new com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor(
                        new com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler() {
                            @Override
                            public net.sf.jsqlparser.expression.Expression getTenantId() {
                                return new net.sf.jsqlparser.expression.LongValue(
                                        TenantContextHolder.getTenantId());
                            }
                        }));
        c.addInterceptor(tenant);

        db.execute("CREATE SYNONYM mom_mes.mes_task FOR public.mes_task");
        db.execute(
                "CREATE SYNONYM mom_mes.mes_aps_source_quantity_event FOR"
                        + " public.mes_aps_source_quantity_event");
        db.execute("CREATE TABLE business_lock(task_code VARCHAR PRIMARY KEY)");
        db.update("INSERT INTO business_lock VALUES('T1')");
        db.execute(
                "CREATE ALIAS HASHTEXTEXTENDED FOR"
                    + " 'com.kitex.mom.mes.service.impl.MesInspectionEventApplicationTransactionTest.lockHash'");
        db.execute(
                "CREATE ALIAS PG_ADVISORY_XACT_LOCK FOR"
                    + " 'com.kitex.mom.mes.service.impl.MesInspectionEventApplicationTransactionTest.lockTable'");
        db.execute(
                "CREATE ALIAS mom_mes.mom_source_quantity_plan_outbox_ready_v1 FOR"
                    + " 'com.kitex.mom.mes.service.impl.MesReworkSourcePlanTransactionTest.ready'");
        db.execute(
                "CREATE ALIAS mom_mes.mom_rework_source_plan_ready_v1 FOR"
                    + " 'com.kitex.mom.mes.service.impl.MesReworkSourcePlanTransactionTest.ready'");
        db.execute(
                "CREATE TABLE mom_mes.mes_rework_source_plan_outbox(id BIGINT PRIMARY KEY,tenant_id"
                    + " BIGINT NOT NULL,factory_code VARCHAR(100) NOT NULL,request_id VARCHAR(120)"
                    + " NOT NULL,request_fingerprint VARCHAR(64) NOT NULL,task_id BIGINT NOT"
                    + " NULL,task_code VARCHAR(100) NOT NULL,payload CLOB NOT"
                    + " NULL,payload_fingerprint VARCHAR(64) NOT NULL,status VARCHAR(16) NOT"
                    + " NULL,attempt_count BIGINT NOT NULL,next_attempt_at TIMESTAMP,created_at"
                    + " TIMESTAMP NOT NULL,received_at TIMESTAMP,last_error"
                    + " VARCHAR(500),UNIQUE(tenant_id,factory_code,request_id))");
        try {
            var type = Class.forName("com.kitex.mom.mes.mapper.MesReworkSourcePlanOutboxMapper");
            c.addMapper(type);
            newMapper = fixture.sessions.getMapper(type);
        } catch (ClassNotFoundException absent) {
            /* RED runs against original production source. */
        }
        remote = mock(RemotePlnIssueService.class);
        when(remote.edit(anyList()))
                .thenAnswer(
                        call -> {
                            calls.incrementAndGet();
                            List<OmsOrderLifeCycleDTO> rows = call.getArgument(0);
                            String raw = json.writeValueAsString(rows);
                            sent.add(raw);
                            if (mode.equals("NULL")) return null;
                            if (mode.equals("EMPTY")) return R.ok(null);
                            if (mode.equals("FAILURE_DATA")) return R.ok(true).setCode(1);
                            for (var row : rows) {
                                String body = json.writeValueAsString(row);
                                String old = accepted.putIfAbsent(row.getRequestId(), body);
                                assertTrue(
                                        old == null || old.equals(body),
                                        "one subrequest must retain the first callback");
                            }
                            if (mode.equals("UNKNOWN"))
                                throw new IllegalStateException("accepted but response lost");
                            return R.ok(true);
                        });
        plans = newPlans();
        source =
                proxy(
                        new MesApsSourceQuantityServiceImpl(
                                fixture.sessions.getMapper(MesTaskMapper.class),
                                fixture.sessions.getMapper(MesApsBatchAllocationMapper.class),
                                fixture.sessions.getMapper(MesApsSourceQuantityEventMapper.class),
                                remote,
                                plans));
        var sender =
                proxy(
                        new MesReworkCompletionOutbox(
                                fixture.sessions.getMapper(MesReworkCompletionOutboxMapper.class),
                                fixture.sessions.getMapper(MesTaskMapper.class),
                                fixture.sessions.getMapper(MesProduceTaskMapper.class),
                                source,
                                fixture.remote));
        ReflectionTestUtils.setField(
                AopProxyUtils.getSingletonTarget(fixture.service),
                "reworkCompletions",
                new MesReworkCompletionCoordinator(sender));
        db.update("UPDATE mes_task SET plan_no='PLAN0',notice_num=10,mat_code='MAT' WHERE id=2");
        db.update(
                "INSERT INTO"
                    + " mes_aps_source_quantity_event(id,tenant_id,factory_code,request_id,request_fingerprint,event_kind,mes_task_id,mes_task_code,source_issue_no,quantity_delta,quantity_after,source_reference)"
                    + " VALUES(20,1,'F1','CREATED',?,'REWORK_CREATED',2,'MT0','PLAN0',2,2,'RW1')",
                "a".repeat(64));
    }

    MesSourceQuantityPlanOutbox newPlans() throws Exception {
        var ctor = MesSourceQuantityPlanOutbox.class.getDeclaredConstructors()[0];
        return proxy(
                (MesSourceQuantityPlanOutbox)
                        ctor.newInstance(
                                Arrays.stream(ctor.getParameterTypes())
                                        .map(
                                                t ->
                                                        t == RemotePlnIssueService.class
                                                                ? remote
                                                                : t
                                                                                == MesSourceQuantityPlanOutboxMapper
                                                                                        .class
                                                                        ? fixture.sessions
                                                                                .getMapper(
                                                                                        MesSourceQuantityPlanOutboxMapper
                                                                                                .class)
                                                                        : newMapper)
                                        .toArray()));
    }

    @SuppressWarnings("unchecked")
    <T> T proxy(T target) {
        var p = new ProxyFactory(target);
        p.setProxyTargetClass(true);
        p.addAdvice(
                new TransactionInterceptor(
                        fixture.transactions, new AnnotationTransactionAttributeSource()));
        return (T) p.getProxy();
    }

    @AfterEach
    void close() {
        SecurityContextHolder.clearContext();
        if (fixture != null) fixture.close();
    }

    MesTaskEntity task() {
        return fixture.sessions.getMapper(MesTaskMapper.class).selectById(2L);
    }

    void defer(String content) {
        assertDoesNotThrow(
                () ->
                        new TransactionTemplate(fixture.transactions)
                                .executeWithoutResult(
                                        tx ->
                                                source.recordDeferred(
                                                        task(),
                                                        REQUEST,
                                                        MesSourceQuantityKind.REWORK_COMPLETED,
                                                        BigDecimal.valueOf(2),
                                                        "RW1",
                                                        content)));
    }

    int count(String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    long queued() {
        assertEquals(
                1,
                count("mom_mes.mes_rework_source_plan_outbox"),
                "rework source needs a committed original");
        return db.queryForObject(
                "SELECT id FROM mom_mes.mes_rework_source_plan_outbox", Long.class);
    }

    String payload() {
        queued();
        return db.queryForObject(
                "SELECT payload FROM mom_mes.mes_rework_source_plan_outbox", String.class);
    }

    boolean deliver(long id) {
        return plans.deliverRework(id);
    }

    @Test
    void actualSenderCommitsBothOriginalsWithoutCallingPlanInline() {
        assertEquals(
                901L, fixture.service.notifyReworkCompletion(1L, "RP1", BigDecimal.valueOf(2)));
        assertEquals(0, calls.get());
        queued();
        assertEquals(1, count("mom_mes.mes_rework_completion_outbox"));
        assertEquals(1, countCompleted());
        assertEquals(0, count("mom_mes.mes_source_quantity_plan_outbox"));
    }

    int countCompleted() {
        return db.queryForObject(
                "SELECT COUNT(*) FROM mes_aps_source_quantity_event WHERE"
                        + " event_kind='REWORK_COMPLETED'",
                Integer.class);
    }

    @Test
    void bothOriginalsAndCompletedFactsDisappearOnCallerRollback() {
        new TransactionTemplate(fixture.transactions)
                .executeWithoutResult(
                        tx -> {
                            assertNull(
                                    fixture.service.notifyReworkCompletion(
                                            1L, "RP1", BigDecimal.valueOf(2)));
                            assertEquals(0, calls.get());
                            queued();
                            tx.setRollbackOnly();
                        });
        assertEquals(0, calls.get());
        assertEquals(0, fixture.sends.get());
        assertEquals(0, countCompleted());
        assertEquals(0, count("mom_mes.mes_rework_source_plan_outbox"));
        assertEquals(0, count("mom_mes.mes_rework_completion_outbox"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "MesApsSourceQuantityEventMapper.insert",
                "MesReworkSourcePlanOutboxMapper.insert",
                "MesReworkCompletionOutboxMapper.insert"
            })
    void necessaryZeroWriteRollsBackTheWholeCompletionBeforeEitherRemote(String statement) {
        fault.zero = statement;
        assertThrows(
                RuntimeException.class,
                () -> fixture.service.notifyReworkCompletion(1L, "RP1", BigDecimal.valueOf(2)));
        assertEquals(0, calls.get());
        assertEquals(0, fixture.sends.get());
        assertEquals(0, countCompleted());
        assertEquals(0, count("mom_mes.mes_rework_source_plan_outbox"));
        assertEquals(0, count("mom_mes.mes_rework_completion_outbox"));
    }

    @Test
    void ordinarySourceEventZeroCannotBecomeAPlanCallback() {
        fault.zero = "MesApsSourceQuantityEventMapper.insert";
        assertThrows(
                RuntimeException.class,
                () ->
                        source.record(
                                task(),
                                "UNQUAL-1",
                                MesSourceQuantityKind.UNQUALIFIED_REPORT,
                                BigDecimal.ONE,
                                "RP1",
                                "bad"));
        assertEquals(0, calls.get());
        assertEquals(1, count("mes_aps_source_quantity_event"));
    }

    @Test
    void anotherFactoryLegacyFactCannotConsumeThisFactoryQuantityAllowance() {
        db.update(
                "INSERT INTO"
                    + " mes_aps_source_quantity_event(id,tenant_id,factory_code,request_id,request_fingerprint,event_kind,mes_task_id,mes_task_code,quantity_delta,quantity_after)"
                    + " VALUES(30,1,'F2','FOREIGN',?,'UNQUALIFIED_REPORT',2,'MT0',10,10)",
                "b".repeat(64));
        assertDoesNotThrow(
                () ->
                        source.record(
                                task(),
                                "LOCAL-UNQUAL",
                                MesSourceQuantityKind.UNQUALIFIED_REPORT,
                                BigDecimal.ONE,
                                "RP1",
                                "local"));
        assertEquals(1, calls.get());
        assertEquals(
                0,
                BigDecimal.ONE.compareTo(
                        db.queryForObject(
                                "SELECT SUM(quantity_delta) FROM mes_aps_source_quantity_event"
                                        + " WHERE event_kind='UNQUALIFIED_REPORT' AND"
                                        + " factory_code='F1'",
                                BigDecimal.class)));
        assertEquals(
                0,
                BigDecimal.TEN.compareTo(
                        db.queryForObject(
                                "SELECT quantity_delta FROM mes_aps_source_quantity_event WHERE"
                                        + " id=30",
                                BigDecimal.class)));
    }

    @Test
    void failedOrdinaryPlanResponseWithDataStillRollsBack() {
        mode = "FAILURE_DATA";
        assertThrows(
                RuntimeException.class,
                () ->
                        source.record(
                                task(),
                                "UNQUAL-1",
                                MesSourceQuantityKind.UNQUALIFIED_REPORT,
                                BigDecimal.ONE,
                                "RP1",
                                "bad"));
        assertEquals(1, count("mes_aps_source_quantity_event"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "NULL", "EMPTY", "FAILURE_DATA", "SAVE_ZERO"})
    void committedPlanOriginalRecoversOnlyItsFirstContentAfterRestart(String failure)
            throws Exception {
        defer("original content");
        long id = queued();
        String raw = payload();
        mode = failure;
        if (failure.equals("SAVE_ZERO")) {
            fault.zero = "MesReworkSourcePlanOutboxMapper.progress";
            assertThrows(RuntimeException.class, () -> deliver(id));
            fault.zero = "";
        } else {
            assertFalse(deliver(id));
            assertFalse(deliver(id));
            assertEquals(1, calls.get());
        }
        defer("edited later content");
        assertEquals(raw, payload());
        plans = newPlans();
        ReflectionTestUtils.setField(
                plans,
                "clock",
                java.time.Clock.offset(
                        java.time.Clock.systemUTC(), java.time.Duration.ofMinutes(2)));
        mode = "OK";
        assertTrue(deliver(id));
        assertFalse(deliver(id));
        assertEquals(2, calls.get());
        assertEquals(sent.get(0), sent.get(1));
        assertEquals(1, accepted.size());
        assertEquals(1, countCompleted());
    }

    void batch() {
        db.update("UPDATE mes_task SET aps_batch_no='B1' WHERE id=2");
        for (int i = 1; i <= 2; i++)
            db.update(
                    "INSERT INTO"
                        + " mes_aps_batch_allocation(id,tenant_id,factory_code,aps_batch_no,mes_task_id,mes_task_code,allocation_seq,issue_id,issue_no,allocated_quantity,rework_quantity,rework_completed_quantity)"
                        + " VALUES(?,1,'F1','B1',2,'MT0',?,?,?,5,1,0)",
                    100 + i,
                    i,
                    200 + i,
                    "PLAN" + i);
    }

    @Test
    void splitSourcesCommitExactAmountsAndPermanentSubrequests() throws Exception {
        batch();
        defer("batch");
        assertEquals(0, calls.get());
        assertTrue(deliver(queued()));
        assertEquals(2, accepted.size());
        var rows = json.readTree(sent.get(0));
        assertEquals("MES-SOURCE-QTY:" + REQUEST + ":201", rows.get(0).path("requestId").asText());
        assertEquals("PLAN1", rows.get(0).path("no").asText());
        assertEquals(
                0, BigDecimal.ONE.compareTo(rows.get(0).path("reworkCompletedNum").decimalValue()));
        assertEquals(
                0, BigDecimal.ONE.compareTo(rows.get(1).path("reworkCompletedNum").decimalValue()));
    }

    void fourSourceHistory(String[] created, String[] completed) {
        db.update("UPDATE mes_task SET aps_batch_no='B1',notice_num=3.00000001 WHERE id=2");
        db.update("DELETE FROM mes_aps_source_quantity_event WHERE id=20");
        for (int i = 0; i < 4; i++) {
            db.update(
                    "INSERT INTO"
                        + " mes_aps_batch_allocation(id,tenant_id,factory_code,aps_batch_no,mes_task_id,mes_task_code,allocation_seq,issue_id,issue_no,allocated_quantity,rework_quantity,rework_completed_quantity)"
                        + " VALUES(?,1,'F1','B1',2,'MT0',?,?,?,?,?,?)",
                    101 + i,
                    i + 1,
                    201 + i,
                    "PLAN" + (i + 1),
                    new BigDecimal(i == 3 ? "0.00000001" : "1"),
                    new BigDecimal(created[i]),
                    new BigDecimal(completed[i]));
            for (int kind = 0; kind < 2; kind++) {
                BigDecimal amount = new BigDecimal(kind == 0 ? created[i] : completed[i]);
                if (amount.signum() == 0) continue;
                db.update(
                        "INSERT INTO"
                            + " mes_aps_source_quantity_event(id,tenant_id,factory_code,request_id,request_fingerprint,event_kind,mes_task_id,mes_task_code,source_issue_id,source_issue_no,quantity_delta,quantity_after,source_reference)"
                            + " VALUES(?,1,'F1',?,?,?,2,'MT0',?,?,?,?, 'OLD-RW')",
                        30 + i * 2 + kind,
                        "OLD-" + kind,
                        "a".repeat(64),
                        kind == 0 ? "REWORK_CREATED" : "REWORK_COMPLETED",
                        201 + i,
                        "PLAN" + (i + 1),
                        amount,
                        amount);
            }
        }
    }

    void assertSourceBudgets(List<BigDecimal> before, BigDecimal added) {
        var after =
                db.queryForList(
                        "SELECT rework_completed_quantity FROM mes_aps_batch_allocation ORDER BY"
                                + " allocation_seq",
                        BigDecimal.class);
        var created =
                db.queryForList(
                        "SELECT rework_quantity FROM mes_aps_batch_allocation ORDER BY"
                                + " allocation_seq",
                        BigDecimal.class);
        BigDecimal increase = BigDecimal.ZERO;
        for (int i = 0; i < after.size(); i++) {
            assertTrue(
                    after.get(i).compareTo(before.get(i)) >= 0,
                    "completed history cannot decrease");
            assertTrue(
                    after.get(i).compareTo(created.get(i)) <= 0,
                    "each source retains its rework budget");
            increase = increase.add(after.get(i).subtract(before.get(i)));
        }
        assertEquals(0, added.compareTo(increase));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mes_aps_source_quantity_event WHERE request_id LIKE"
                                + " 'REWORK-COMPLETED:%' AND quantity_delta<=0",
                        Integer.class));
    }

    @Test
    void sequentialIntegerCompletionsKeepRoundedSourceHistoryAndRecoverBothOriginals()
            throws Exception {
        fourSourceHistory(
                new String[] {"1.66666666", "1.66666666", "1.66666666", "0.00000002"},
                new String[] {"0.66666666", "0.66666666", "0.66666666", "0.00000002"});
        db.update("UPDATE mes_task SET notice_num=1,complete_num=1 WHERE id=1");
        db.update("UPDATE mes_produce_task SET notice_num=1,complete_num=1 WHERE id=3");
        for (int round = 0; round < 3; round++) {
            long taskId = round == 0 ? 1 : 10 + round;
            String operation = "RP" + (round + 1);
            String request = "REWORK-COMPLETED:QMS-RW-PROCEDURE-" + (80 + round);
            if (round > 0) {
                db.update(
                        "INSERT INTO"
                            + " mes_task(id,tenant_id,factory_code,code,resource_type,source_task_code,rework_request_id,status,notice_num,complete_num,mat_code,source_inspection_type,source_inspection_task_id,source_handle_record_id,source_produce_task_code,rework_process_code,rework_reason)"
                            + " SELECT"
                            + " ?,tenant_id,factory_code,?,'REWORK',source_task_code,?,'FINISH',1,1,mat_code,source_inspection_type,source_inspection_task_id,?,source_produce_task_code,rework_process_code,rework_reason"
                            + " FROM mes_task WHERE id=1",
                        taskId,
                        "RW" + (round + 1),
                        "QMS-RW-PROCEDURE-" + (80 + round),
                        80 + round);
                db.update(
                        "INSERT INTO"
                            + " mes_produce_task(id,tenant_id,factory_code,code,task_code,status,last_route,route_code,route_name,notice_num,complete_num)"
                            + " VALUES(?,1,'F1',?,?,'FINISH',TRUE,'PROC','接线',1,1)",
                        20 + round,
                        operation,
                        "RW" + (round + 1));
            }
            var before =
                    db.queryForList(
                            "SELECT rework_completed_quantity FROM mes_aps_batch_allocation ORDER"
                                    + " BY allocation_seq",
                            BigDecimal.class);
            assertEquals(
                    901L,
                    assertDoesNotThrow(
                            () ->
                                    fixture.service.notifyReworkCompletion(
                                            taskId, operation, BigDecimal.ONE)));
            assertSourceBudgets(before, BigDecimal.ONE);
            assertEquals(round + 1, count("mom_mes.mes_rework_completion_outbox"));
            assertEquals(round + 1, count("mom_mes.mes_rework_source_plan_outbox"));
            long id =
                    db.queryForObject(
                            "SELECT id FROM mom_mes.mes_rework_source_plan_outbox WHERE"
                                    + " request_id=?",
                            Long.class,
                            request);
            plans = newPlans();
            mode = "UNKNOWN";
            assertFalse(deliver(id));
            String firstSend = sent.get(sent.size() - 1);
            plans = newPlans();
            ReflectionTestUtils.setField(
                    plans,
                    "clock",
                    java.time.Clock.offset(
                            java.time.Clock.systemUTC(), java.time.Duration.ofMinutes(2)));
            mode = "OK";
            assertTrue(deliver(id));
            assertEquals(firstSend, sent.get(sent.size() - 1));
        }
        assertEquals(6, calls.get());
        assertEquals(9, accepted.size());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mes_aps_batch_allocation WHERE"
                                + " rework_completed_quantity<>rework_quantity",
                        Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"early", "late"})
    void completionRedistributesOnlyWithinEachRemainingSourceBudget(String capped) {
        fourSourceHistory(
                capped.equals("early")
                        ? new String[] {"0.01", "1", "1", "0"}
                        : new String[] {"1", "1", "0.01", "0"},
                new String[] {"0", "0", "0", "0"});
        assertEquals(901L, fixture.service.notifyReworkCompletion(1L, "RP1", new BigDecimal("2")));
        assertSourceBudgets(
                List.of(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                new BigDecimal("2"));
        assertEquals(0, calls.get());
        assertTrue(deliver(queued()));
    }

    @Test
    void secondSplitEventZeroRollsBackTheFirstEventAndBothAllocations() {
        batch();
        fault.zero = "MesApsSourceQuantityEventMapper.insert";
        fault.skip = 1;
        assertThrows(
                RuntimeException.class,
                () ->
                        new TransactionTemplate(fixture.transactions)
                                .executeWithoutResult(
                                        tx ->
                                                source.recordDeferred(
                                                        task(),
                                                        REQUEST,
                                                        MesSourceQuantityKind.REWORK_COMPLETED,
                                                        BigDecimal.valueOf(2),
                                                        "RW1",
                                                        "batch")));
        assertEquals(0, countCompleted());
        assertEquals(
                0,
                BigDecimal.ZERO.compareTo(
                        db.queryForObject(
                                "SELECT SUM(rework_completed_quantity) FROM"
                                        + " mes_aps_batch_allocation",
                                BigDecimal.class)));
        assertEquals(0, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant", "factory", "tenantSkip", "factorySkip"})
    void actualScopeCannotBeForged(String changed) {
        var t = task();
        switch (changed) {
            case "tenant" -> t.setTenantId(2L);
            case "factory" -> t.setFactoryCode("F2");
            case "tenantSkip" -> TenantContextHolder.setTenantSkip();
            case "factorySkip" -> FactoryContextHolder.setFactorySkip();
        }
        assertThrows(
                RuntimeException.class,
                () ->
                        new TransactionTemplate(fixture.transactions)
                                .executeWithoutResult(
                                        tx ->
                                                source.recordDeferred(
                                                        t,
                                                        REQUEST,
                                                        MesSourceQuantityKind.REWORK_COMPLETED,
                                                        BigDecimal.valueOf(2),
                                                        "RW1",
                                                        "scope")));
        assertEquals(0, calls.get());
        assertEquals(0, countCompleted());
    }

    @Test
    void linkedLegacyWithoutOriginalCannotReconstructFromCurrentRows() {
        defer("first");
        db.update("DELETE FROM mom_mes.mes_rework_source_plan_outbox");
        assertThrows(
                RuntimeException.class,
                () ->
                        new TransactionTemplate(fixture.transactions)
                                .executeWithoutResult(
                                        tx ->
                                                source.recordDeferred(
                                                        task(),
                                                        REQUEST,
                                                        MesSourceQuantityKind.REWORK_COMPLETED,
                                                        BigDecimal.valueOf(2),
                                                        "RW1",
                                                        "other")));
        assertEquals(0, calls.get());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "fingerprint",
                "quantity",
                "sourceDeleted",
                "originalDeleted",
                "duplicateCallback",
                "extraQuantity",
                "oversize"
            })
    void damagedOriginalOrActualSourceNeverCallsPlan(String changed) throws Exception {
        defer("first");
        long id = queued();
        String raw = payload();
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(raw);
        switch (changed) {
            case "fingerprint" ->
                    db.update(
                            "UPDATE mom_mes.mes_rework_source_plan_outbox SET"
                                    + " payload_fingerprint=?",
                            "f".repeat(64));
            case "sourceDeleted" ->
                    db.update(
                            "UPDATE mes_aps_source_quantity_event SET del_flag='1' WHERE"
                                    + " event_kind='REWORK_COMPLETED'");
            case "originalDeleted" -> db.update("UPDATE mes_task SET del_flag='1' WHERE id=2");
            case "oversize" ->
                    db.update(
                            "UPDATE mom_mes.mes_rework_source_plan_outbox SET payload=?",
                            "x".repeat(1048577));
            default -> {
                var callbacks =
                        (com.fasterxml.jackson.databind.node.ArrayNode) body.path("callbacks");
                var c = (com.fasterxml.jackson.databind.node.ObjectNode) callbacks.get(0);
                if (changed.equals("quantity")) c.put("reworkCompletedNum", 1);
                if (changed.equals("extraQuantity")) c.put("qualityReleasedNum", 2);
                if (changed.equals("duplicateCallback")) callbacks.add(c.deepCopy());
                String modified = body.toString();
                db.update(
                        "UPDATE mom_mes.mes_rework_source_plan_outbox SET"
                                + " payload=?,payload_fingerprint=?",
                        modified,
                        MesInspectionEventProjection.hash(modified));
            }
        }
        assertFalse(deliver(id));
        assertEquals(0, calls.get());
        assertEquals(1, countCompleted());
    }

    @Test
    void authenticatedRequestCannotConsumeBackgroundOutbox() {
        defer("first");
        long id = queued();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken("operator", "N/A", List.of()));
        assertThrows(RuntimeException.class, () -> deliver(id));
        assertEquals(0, calls.get());
    }

    @Test
    void existingDispatcherDeliversReworkAndRestoresThePreviousScope() {
        defer("dispatch");
        var states = mock(MesInspectionEventStateMapper.class);
        when(states.schemaVersion()).thenReturn(1);
        when(states.dueAfter(anyLong())).thenReturn(List.of());
        var dispatcher =
                new MesInspectionEventApplicationDispatcher(
                        states, mock(MesInspectionEventApplicationWorker.class), plans);
        TenantContextHolder.setTenantId(9L);
        FactoryContextHolder.setFactoryCode("F9");
        assertEquals(0, dispatcher.scan());
        assertEquals(1, calls.get());
        assertEquals(9L, TenantContextHolder.getTenantId());
        assertEquals("F9", FactoryContextHolder.getFactoryCode());
        assertEquals(
                "RECEIVED",
                db.queryForObject(
                        "SELECT status FROM mom_mes.mes_rework_source_plan_outbox", String.class));
    }

    @Test
    void tinySplitAmountKeepsItsOriginalDecimalRepresentationOnUnknownRecovery() throws Exception {
        batch();
        db.update(
                "UPDATE mes_aps_batch_allocation SET"
                        + " allocated_quantity=0.00000005,rework_quantity=0.00000001 WHERE id=101");
        db.update(
                "UPDATE mes_aps_batch_allocation SET"
                        + " allocated_quantity=9.99999995,rework_quantity=1.99999999 WHERE id=102");
        defer("tiny");
        long id = queued();
        String raw = payload();
        mode = "UNKNOWN";
        assertFalse(deliver(id));
        plans = newPlans();
        ReflectionTestUtils.setField(
                plans,
                "clock",
                java.time.Clock.offset(
                        java.time.Clock.systemUTC(), java.time.Duration.ofMinutes(2)));
        mode = "OK";
        assertTrue(deliver(id));
        assertEquals(raw, payload());
        assertEquals(sent.get(0), sent.get(1));
        assertEquals(
                0,
                new BigDecimal("0.00000001")
                        .compareTo(
                                json.readTree(sent.get(0))
                                        .get(0)
                                        .path("reworkCompletedNum")
                                        .decimalValue()));
    }

    @Test
    void missingReworkReadinessStopsAnotherSourceReplayOrSend() {
        defer("first");
        long id = queued();
        db.execute("DROP ALIAS mom_mes.mom_rework_source_plan_ready_v1");
        assertThrows(
                RuntimeException.class,
                () ->
                        source.recordDeferred(
                                task(),
                                REQUEST,
                                MesSourceQuantityKind.REWORK_COMPLETED,
                                BigDecimal.valueOf(2),
                                "RW1",
                                "again"));
        assertThrows(RuntimeException.class, () -> deliver(id));
        assertEquals(0, calls.get());
        assertEquals(1, countCompleted());
    }

    @Test
    void concurrentDeliveryConfirmsOnce() throws Exception {
        defer("first");
        long id = queued();
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            calls.incrementAndGet();
                            held.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return R.ok(true);
                        })
                .when(remote)
                .edit(anyList());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> sendInScope(id));
            assertTrue(held.await(5, TimeUnit.SECONDS));
            var b = pool.submit(() -> sendInScope(id));
            assertThrows(TimeoutException.class, () -> b.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(a.get(5, TimeUnit.SECONDS));
            assertFalse(b.get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    boolean sendInScope(long id) {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        try {
            return deliver(id);
        } finally {
            TenantContextHolder.clear();
            FactoryContextHolder.clear();
        }
    }

    @Intercepts(
            @Signature(
                    type = Executor.class,
                    method = "update",
                    args = {MappedStatement.class, Object.class}))
    static class Fault implements Interceptor {
        String zero = "";
        int skip;

        public Object intercept(Invocation i) throws Throwable {
            var s = (MappedStatement) i.getArgs()[0];
            if (!zero.isBlank() && s.getId().endsWith(zero) && skip-- <= 0) return 0;
            return i.proceed();
        }
    }
}
