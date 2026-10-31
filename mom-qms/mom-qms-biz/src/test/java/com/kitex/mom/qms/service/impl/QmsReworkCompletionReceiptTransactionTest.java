package com.kitex.mom.qms.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.qms.dto.QmsReworkCompletionDTO;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.service.QmsReworkOrderService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

/** Actual completion, child/basis/history and receipt transactions, not the runtime database. */
class QmsReworkCompletionReceiptTransactionTest {
    private QmsReworkCoordinationTransactionTest fixture;
    private JdbcTemplate db;
    private QmsReworkOrderService service;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setup() throws Exception {
        fixture = new QmsReworkCoordinationTransactionTest();
        fixture.setup();
        db = (JdbcTemplate) ReflectionTestUtils.getField(fixture, "db");
        service = (QmsReworkOrderService) ReflectionTestUtils.getField(fixture, "service");
    }

    @AfterEach
    void close() {
        if (fixture != null) fixture.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void completionAndItsActualReinspectionMustPersistOneOriginalReceipt(String type) {
        var row = order(type);
        var command = command(row);
        long child = service.completeFromMes(command);
        assertEquals(1, count());
        var saved = db.queryForMap("SELECT * FROM mom_qms.qms_rework_completion_receipt");
        assertEquals(row.getId(), saved.get("order_id"));
        assertEquals(child, saved.get("child_task_id"));
        String payload =
                (String)
                        db.queryForObject(
                                "SELECT CAST(payload AS VARCHAR) FROM"
                                        + " mom_qms.qms_rework_completion_receipt",
                                String.class);
        var snapshot = read(payload);
        assertEquals("P-END", snapshot.path("command").path("mesProduceTaskCode").asText());
        assertEquals(2, snapshot.path("command").path("completedQuantity").asInt());
        assertTrue(snapshot.path("command").path("processName").isNull());
        assertEquals(row.getId().longValue(), snapshot.path("orderId").asLong());
        assertEquals(child, service.completeFromMes(command));
        assertEquals(1, count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2.0", "2.00"})
    void equalBusinessQuantitiesReplayWithoutASecondReceipt(String number) {
        var row = order("PROCEDURE");
        var c = command(row);
        long child = service.completeFromMes(c);
        c.setCompletedQuantity(new BigDecimal(number));
        assertEquals(child, service.completeFromMes(c));
        assertEquals(1, count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOURCE", "NAME"})
    void changingBothCurrentChildAndCallbackCannotRewriteTheOriginalCommand(String field) {
        var row = order("PROCEDURE");
        var c = command(row);
        long child = service.completeFromMes(c);
        if ("SOURCE".equals(field)) {
            c.setMesProduceTaskCode("P-OTHER");
            db.update(
                    "UPDATE mom_qms.qms_inspection_procedure_task SET source_task_code=? WHERE"
                            + " id=?",
                    "P-OTHER",
                    child);
        } else {
            c.setProcessName("");
            db.update(
                    "UPDATE mom_qms.qms_inspection_procedure_task SET process_name='' WHERE id=?",
                    child);
        }
        assertThrows(RuntimeException.class, () -> service.completeFromMes(c));
    }

    @Test
    void aLegacyMissingOriginalIsNotBackfilledByAnUnprovenReplay() {
        var row = order("PROCEDURE");
        var c = command(row);
        service.completeFromMes(c);
        db.update("DELETE FROM mom_qms.qms_rework_completion_receipt");
        assertThrows(RuntimeException.class, () -> service.completeFromMes(c));
        assertEquals(0, count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void necessaryReceiptInsertFailureRollsBackChildBasisHistoryAndProgress(String type) {
        var row = order(type);
        var fault = ReflectionTestUtils.getField(fixture, "fault");
        ReflectionTestUtils.setField(fault, "lose", "QmsReworkCompletionReceiptMapper.insert");
        int children = taskCount(type);
        int histories =
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_"
                                + type.toLowerCase(Locale.ROOT)
                                + "_history",
                        Integer.class);
        assertThrows(RuntimeException.class, () -> service.completeFromMes(command(row)));
        assertEquals(children, taskCount(type));
        assertEquals(0, count());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_basis", Integer.class));
        assertEquals(
                histories,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_"
                                + type.toLowerCase(Locale.ROOT)
                                + "_history",
                        Integer.class));
        assertNull(
                db.queryForObject(
                        "SELECT reinspection_task_id FROM mom_qms.qms_rework_order WHERE id=?",
                        Long.class,
                        row.getId()));
        assertEquals(
                "MES_CREATED",
                db.queryForObject(
                        "SELECT status FROM mom_qms.qms_rework_order WHERE id=?",
                        String.class,
                        row.getId()));
    }

    @Test
    void anOuterTransactionRollbackAlsoRemovesTheReceipt() {
        var row = order("PROCEDURE");
        var tx =
                new TransactionTemplate(
                        (PlatformTransactionManager)
                                ReflectionTestUtils.getField(fixture, "transactions"));
        assertThrows(
                ForcedAbort.class,
                () ->
                        tx.execute(
                                s -> {
                                    service.completeFromMes(command(row));
                                    assertEquals(1, count());
                                    throw new ForcedAbort();
                                }));
        assertEquals(0, count());
        assertEquals(1, taskCount("PROCEDURE"));
    }

    @Test
    void unavailableReceiptSchemaStopsCompletionBeforeCreatingAChild() {
        var row = order("PROCEDURE");
        db.execute("DROP ALIAS mom_qms.mom_rework_completion_receipt_ready_v1");
        assertThrows(RuntimeException.class, () -> service.completeFromMes(command(row)));
        assertEquals(1, taskCount("PROCEDURE"));
        assertEquals(0, count());
    }

    @Test
    void boundedProcessNameIsCheckedBeforeAnyChildWrites() {
        var row = order("PROCEDURE");
        var c = command(row);
        c.setProcessName("x".repeat(1001));
        assertThrows(RuntimeException.class, () -> service.completeFromMes(c));
        assertEquals(1, taskCount("PROCEDURE"));
        assertEquals(0, count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAYLOAD", "HASH", "CHILD", "TIME"})
    void aDamagedOriginalCannotBeUsedForReadOrReplay(String damage) {
        var row = order("PROCEDURE");
        var c = command(row);
        service.completeFromMes(c);
        assertEquals(1, count());
        if ("PAYLOAD".equals(damage))
            db.update(
                    "UPDATE mom_qms.qms_rework_completion_receipt SET payload=CAST(payload AS"
                            + " VARCHAR)||' '");
        if ("HASH".equals(damage))
            db.update(
                    "UPDATE mom_qms.qms_rework_completion_receipt SET payload_fingerprint=?",
                    "0".repeat(64));
        if ("CHILD".equals(damage))
            db.update("UPDATE mom_qms.qms_rework_completion_receipt SET child_task_id=999");
        if ("TIME".equals(damage))
            db.update(
                    "UPDATE mom_qms.qms_rework_completion_receipt SET received_at=TIMESTAMP"
                            + " '2020-01-01 00:00:00'");
        assertThrows(RuntimeException.class, () -> service.completeFromMes(c));
        assertThrows(RuntimeException.class, () -> view("PROCEDURE", row.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void receiptReadShowsOriginalValuesWithExactActualScope(String type) {
        var row = order(type);
        long child = service.completeFromMes(command(row));
        var v = view(type, row.getId());
        assertEquals("FROZEN", v.path("availability").asText());
        assertEquals("F1", v.path("factoryCode").asText());
        assertEquals(row.getId().longValue(), v.path("orderId").asLong());
        assertEquals(child, v.path("childTaskId").asLong());
        assertEquals(
                "P-END", v.path("snapshot").path("command").path("mesProduceTaskCode").asText());
    }

    @Test
    void legacyReadKeepsAbsenceExplicitWithoutReconstructingACommand() {
        var row = order("PROCEDURE");
        service.completeFromMes(command(row));
        db.update("DELETE FROM mom_qms.qms_rework_completion_receipt");
        var v = view("PROCEDURE", row.getId());
        assertEquals("LEGACY_UNAVAILABLE", v.path("availability").asText());
        assertTrue(v.path("snapshot").isNull());
        assertEquals(0, count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTION", "FACTORY", "TYPE", "CHILD_DELETED", "HANDLE_DELETED"})
    void deniedOrOrphanReceiptReadCannotExposeTheOriginal(String denial) {
        var row = order("PROCEDURE");
        long child = service.completeFromMes(command(row));
        if ("ACTION".equals(denial)) {
            var p = mock(PermissionService.class);
            ReflectionTestUtils.setField(
                    AopProxyUtils.getSingletonTarget(reader()), "permissions", p);
        }
        if ("FACTORY".equals(denial)) FactoryContextHolder.setFactoryCode("F2");
        if ("HANDLE_DELETED".equals(denial))
            db.update(
                    "UPDATE mom_qms.qms_inspection_procedure_handle_record SET del_flag='1' WHERE"
                            + " id=80");
        if ("CHILD_DELETED".equals(denial))
            db.update(
                    "UPDATE mom_qms.qms_inspection_procedure_task SET del_flag='1' WHERE id=?",
                    child);
        assertThrows(
                RuntimeException.class,
                () -> view("TYPE".equals(denial) ? "PRODUCT" : "PROCEDURE", row.getId()));
    }

    @Test
    void concurrentOriginalCallbacksCreateOneReceiptAndOneChild() throws Exception {
        var row = order("PROCEDURE");
        var auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Long> action =
                    () -> {
                        TenantContextHolder.setTenantId(1L);
                        FactoryContextHolder.setFactoryCode("F1");
                        org.springframework.security.core.context.SecurityContextHolder.getContext()
                                .setAuthentication(auth);
                        try {
                            return service.completeFromMes(command(row));
                        } finally {
                            TenantContextHolder.clear();
                            FactoryContextHolder.clear();
                            org.springframework.security.core.context.SecurityContextHolder
                                    .clearContext();
                        }
                    };
            var a = pool.submit(action);
            var b = pool.submit(action);
            assertEquals(a.get(8, TimeUnit.SECONDS), b.get(8, TimeUnit.SECONDS));
            assertEquals(1, count());
            assertEquals(2, taskCount("PROCEDURE"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void oversizedRestoredReceiptIsRejectedWithoutTransportingItsLargePayload() {
        var row = order("PROCEDURE");
        service.completeFromMes(command(row));
        db.update(
                "UPDATE mom_qms.qms_rework_completion_receipt SET payload=?,payload_fingerprint=?",
                "x".repeat(130000),
                "0".repeat(64));
        var inner = ReflectionTestUtils.getField(fixture, "fixture");
        var sessions =
                (org.mybatis.spring.SqlSessionTemplate)
                        ReflectionTestUtils.getField(inner, "sessions");
        var observed = new ObservedReceiptPayload();
        ((com.baomidou.mybatisplus.core.MybatisConfiguration) sessions.getConfiguration())
                .addInterceptor(observed);
        assertThrows(RuntimeException.class, () -> view("PROCEDURE", row.getId()));
        assertTrue(observed.queries > 0);
        assertTrue(
                observed.maximumBytes <= 65536,
                "oversized restored body crossed the application query boundary");
    }

    @org.apache.ibatis.plugin.Intercepts(
            @org.apache.ibatis.plugin.Signature(
                    type = org.apache.ibatis.executor.Executor.class,
                    method = "query",
                    args = {
                        org.apache.ibatis.mapping.MappedStatement.class,
                        Object.class,
                        org.apache.ibatis.session.RowBounds.class,
                        org.apache.ibatis.session.ResultHandler.class
                    }))
    static class ObservedReceiptPayload implements org.apache.ibatis.plugin.Interceptor {
        int queries, maximumBytes;

        public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            var statement = (org.apache.ibatis.mapping.MappedStatement) invocation.getArgs()[0];
            if (statement.getId().contains("QmsReworkCompletionReceiptMapper")
                    && result instanceof List<?> rows)
                for (var item : rows)
                    if (item instanceof QmsReworkCompletionReceiptEntity row) {
                        queries++;
                        if (row.getPayload() != null)
                            maximumBytes =
                                    Math.max(
                                            maximumBytes,
                                            row.getPayload()
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets.UTF_8)
                                                    .length);
                    }
            return result;
        }
    }

    @Test
    void receiptReadRequiresRealFactoryReadInAdditionToTheShowAction() {
        var row = order("PROCEDURE");
        service.completeFromMes(command(row));
        var history =
                ReflectionTestUtils.getField(
                        ReflectionTestUtils.getField(fixture, "fixture"), "historyFixture");
        ReflectionTestUtils.setField(history, "authorization", R.ok(false));
        assertThrows(RuntimeException.class, () -> view("PROCEDURE", row.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"product", "procedure"})
    void receiptHttpRequiresTheExactTaskTypeAction(String type) throws Exception {
        var p = mock(PermissionService.class);
        ReflectionTestUtils.setField(AopProxyUtils.getSingletonTarget(reader()), "permissions", p);
        var web = web(p);
        try {
            var mvc =
                    org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(
                                    web)
                            .addFilters(
                                    new org.springframework.security.web.access
                                            .ExceptionTranslationFilter(
                                            new org.springframework.security.web.authentication
                                                    .Http403ForbiddenEntryPoint()))
                            .build();
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                            "/inspection/rework-completion-receipt/" + type)
                                    .param("orderId", "700"))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .status()
                                    .isForbidden());
        } finally {
            web.close();
        }
    }

    @Test
    void actualHttpKeepsNestedReceiptIdentifiersPreciseAndTheEpochExact() throws Exception {
        var row = order("PROCEDURE");
        long child = service.completeFromMes(command(row));
        var p = mock(PermissionService.class);
        when(p.hasPermission("procedureTask:show")).thenReturn(true);
        var web = web(p);
        try {
            var mvc =
                    org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(
                                    web)
                            .build();
            String body =
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.get(
                                                    "/inspection/rework-completion-receipt/procedure")
                                            .param("orderId", row.getId().toString()))
                            .andExpect(
                                    org.springframework.test.web.servlet.result
                                            .MockMvcResultMatchers.status()
                                            .isOk())
                            .andExpect(
                                    org.springframework.test.web.servlet.result
                                            .MockMvcResultMatchers.jsonPath("$.data.orderId")
                                            .value(row.getId().toString()))
                            .andExpect(
                                    org.springframework.test.web.servlet.result
                                            .MockMvcResultMatchers.jsonPath("$.data.childTaskId")
                                            .value(Long.toString(child)))
                            .andExpect(
                                    org.springframework.test.web.servlet.result
                                            .MockMvcResultMatchers.jsonPath(
                                                    "$.data.snapshot.childTaskId")
                                            .value(Long.toString(child)))
                            .andExpect(
                                    org.springframework.test.web.servlet.result
                                            .MockMvcResultMatchers.jsonPath(
                                                    "$.data.snapshot.handleRecordId")
                                            .value("80"))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            var actual = read(body).path("data").path("snapshot");
            var stored =
                    read(
                            db.queryForObject(
                                    "SELECT CAST(payload AS VARCHAR) FROM"
                                            + " mom_qms.qms_rework_completion_receipt",
                                    String.class));
            assertEquals(stored.path("receivedAt").asText(), actual.path("receivedAt").asText());
        } finally {
            web.close();
        }
    }

    private org.springframework.web.context.support.GenericWebApplicationContext web(
            PermissionService p) {
        var web = new org.springframework.web.context.support.GenericWebApplicationContext();
        web.setServletContext(new org.springframework.mock.web.MockServletContext());
        org.springframework.context.annotation.AnnotationConfigUtils
                .registerAnnotationConfigProcessors(web);
        web.registerBean(QmsInspectionHistoryTransactionTest.WebConfiguration.class);
        web.registerBean("pms", PermissionService.class, () -> p);
        web.registerBean(
                com.kitex.mom.qms.controller.QmsReworkCompletionReceiptController.class,
                () ->
                        new com.kitex.mom.qms.controller.QmsReworkCompletionReceiptController(
                                (QmsReworkCompletionReceiptService) reader()));
        web.refresh();
        return web;
    }

    @Test
    void receiptReadUsesAnIndependentReadOnlyRepeatableReadTransaction() {
        var row = order("PROCEDURE");
        long child = service.completeFromMes(command(row));
        var inner = ReflectionTestUtils.getField(fixture, "fixture");
        var sessions =
                (org.mybatis.spring.SqlSessionTemplate)
                        ReflectionTestUtils.getField(inner, "sessions");
        var observed = new ReceiptReadTransaction();
        ((com.baomidou.mybatisplus.core.MybatisConfiguration) sessions.getConfiguration())
                .addInterceptor(observed);
        var transactions =
                (org.springframework.transaction.PlatformTransactionManager)
                        ReflectionTestUtils.getField(fixture, "transactions");
        new org.springframework.transaction.support.TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            db.update(
                                    "UPDATE mom_qms.qms_inspection_procedure_task SET"
                                            + " source_task_code='P-OUTER' WHERE id=?",
                                    child);
                            var outer =
                                    org.springframework.jdbc.datasource.DataSourceUtils
                                            .getConnection(db.getDataSource());
                            var found = assertDoesNotThrow(() -> view("PROCEDURE", row.getId()));
                            assertEquals(
                                    "P-END",
                                    found.path("snapshot")
                                            .path("command")
                                            .path("mesProduceTaskCode")
                                            .asText());
                            assertTrue(observed.readOnly);
                            assertEquals(
                                    java.sql.Connection.TRANSACTION_REPEATABLE_READ,
                                    observed.isolation);
                            assertEquals(
                                    1,
                                    observed.connections.size(),
                                    "receipt, child and handle must use the same actual"
                                            + " connection");
                            assertFalse(
                                    observed.connections.contains(outer),
                                    "read must suspend the caller transaction");
                            assertTrue(
                                    observed.statements.contains(
                                            "QmsReworkCompletionReceiptMapper.byOrder"));
                            assertTrue(
                                    observed.statements.contains(
                                            "QmsInspectionTraceMapper.procedureTasks"));
                            assertTrue(
                                    observed.statements.contains(
                                            "QmsInspectionTraceMapper.procedureHandles"));
                            status.setRollbackOnly();
                        });
        assertEquals(
                "P-END",
                db.queryForObject(
                        "SELECT source_task_code FROM mom_qms.qms_inspection_procedure_task WHERE"
                                + " id=?",
                        String.class,
                        child));
    }

    @org.apache.ibatis.plugin.Intercepts(
            @org.apache.ibatis.plugin.Signature(
                    type = org.apache.ibatis.executor.Executor.class,
                    method = "query",
                    args = {
                        org.apache.ibatis.mapping.MappedStatement.class,
                        Object.class,
                        org.apache.ibatis.session.RowBounds.class,
                        org.apache.ibatis.session.ResultHandler.class
                    }))
    static class ReceiptReadTransaction implements org.apache.ibatis.plugin.Interceptor {
        boolean readOnly = true;
        Integer isolation;
        final java.util.Set<java.sql.Connection> connections =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        final java.util.Set<String> statements = new java.util.HashSet<>();

        public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            var statement = (org.apache.ibatis.mapping.MappedStatement) invocation.getArgs()[0];
            String name =
                    statement
                            .getId()
                            .substring(
                                    statement
                                                    .getId()
                                                    .lastIndexOf(
                                                            '.',
                                                            statement.getId().lastIndexOf('.') - 1)
                                            + 1);
            if (name.equals("QmsReworkCompletionReceiptMapper.byOrder")
                    || name.equals("QmsInspectionTraceMapper.procedureTasks")
                    || name.equals("QmsInspectionTraceMapper.procedureHandles")) {
                statements.add(name);
                readOnly &=
                        org.springframework.transaction.support.TransactionSynchronizationManager
                                .isCurrentTransactionReadOnly();
                isolation =
                        org.springframework.transaction.support.TransactionSynchronizationManager
                                .getCurrentTransactionIsolationLevel();
                var connection =
                        ((org.apache.ibatis.executor.Executor) invocation.getTarget())
                                .getTransaction()
                                .getConnection();
                assertEquals(
                        java.sql.Connection.TRANSACTION_REPEATABLE_READ,
                        connection.getTransactionIsolation());
                assertFalse(connection.getAutoCommit());
                connections.add(connection);
            }
            return invocation.proceed();
        }
    }

    private QmsReworkOrderEntity order(String type) {
        var core = ReflectionTestUtils.getField(fixture, "fixture");
        ((QmsQualityStandardEntity) ReflectionTestUtils.getField(core, "standard"))
                .setInspectionType(type);
        return service.create(type, "PROCEDURE".equals(type) ? 80L : 81L);
    }

    private QmsReworkCompletionDTO command(QmsReworkOrderEntity row) {
        var c = new QmsReworkCompletionDTO();
        c.setRequestId(row.getRequestId());
        c.setMesTaskCode(row.getMesTaskCode());
        c.setMesProduceTaskCode("P-END");
        c.setCompletedQuantity(row.getQuantity());
        c.setProcessCode("PROC");
        return c;
    }

    private int count() {
        return db.queryForObject(
                "SELECT COUNT(*) FROM mom_qms.qms_rework_completion_receipt", Integer.class);
    }

    private int taskCount(String type) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM mom_qms.qms_inspection_"
                        + type.toLowerCase(Locale.ROOT)
                        + "_task",
                Integer.class);
    }

    private JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private QmsReworkCompletionReceiptService reader() {
        var core = AopProxyUtils.getSingletonTarget(service);
        var receipts =
                (QmsReworkCompletionReceiptService)
                        ReflectionTestUtils.getField(core, "completionReceipts");
        assertNotNull(receipts, "实际完工回执服务未接入协调事务");
        return receipts;
    }

    private JsonNode view(String type, Long id) {
        return json.valueToTree(reader().view(type, id));
    }

    private static class ForcedAbort extends RuntimeException {}
}
