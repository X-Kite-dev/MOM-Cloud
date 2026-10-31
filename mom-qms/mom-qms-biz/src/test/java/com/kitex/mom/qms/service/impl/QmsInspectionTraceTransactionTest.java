package com.kitex.mom.qms.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.qms.dto.QmsReworkCompletionDTO;
import com.kitex.mom.qms.entity.QmsReworkOrderEntity;
import com.kitex.mom.qms.service.QmsReworkOrderService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.*;

/** Real persisted QMS source, disposition and reinspection relationships; no runtime database. */
class QmsInspectionTraceTransactionTest {
    private QmsReworkCoordinationTransactionTest fixture;
    private JdbcTemplate db;
    private SqlSessionTemplate sessions;
    private QmsReworkOrderService rework;
    private PermissionService permissions;
    private QmsInspectionHistorySupport access;
    private PlatformTransactionManager transactions;
    private QmsInspectionTraceService trace;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setup() throws Exception {
        fixture = new QmsReworkCoordinationTransactionTest();
        fixture.setup();
        db = (JdbcTemplate) ReflectionTestUtils.getField(fixture, "db");
        sessions = (SqlSessionTemplate) ReflectionTestUtils.getField(fixture, "sessions");
        rework = (QmsReworkOrderService) ReflectionTestUtils.getField(fixture, "service");
        transactions =
                (PlatformTransactionManager) ReflectionTestUtils.getField(fixture, "transactions");
        access =
                (QmsInspectionHistorySupport)
                        ReflectionTestUtils.getField(
                                AopProxyUtils.getSingletonTarget(rework), "access");
        permissions = mock(PermissionService.class);
        when(permissions.hasPermission("procedureTask:show")).thenReturn(true);
        when(permissions.hasPermission("productTask:show")).thenReturn(true);
        var remote = (RemoteMesTaskService) ReflectionTestUtils.getField(fixture, "remote");
        when(remote.addRework(any()))
                .thenAnswer(
                        c ->
                                R.ok(
                                        "RW-"
                                                + c.getArgument(
                                                                0,
                                                                com.kitex.mom.mes.dto
                                                                        .MesTaskReceiveDTO.class)
                                                        .getSourceHandleRecordId(),
                                        null));
    }

    @AfterEach
    void close() {
        if (fixture != null) fixture.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void aRealReinspectionCanTraceItsOriginAndPreservesRelativeRounds(String type) {
        var row = create(type);
        long child = rework.completeFromMes(command(row));
        var view = view(type, child);
        assertEquals("F1", view.path("factoryCode").asText());
        assertEquals(type, view.path("inspectionType").asText());
        assertEquals(child, view.path("taskId").asLong());
        assertEquals(parent(type), view.path("rootTaskId").asLong());
        assertFalse(view.path("firstPassEligible").asBoolean(true));
        assertEquals(2, view.path("nodes").size());
        assertEquals(1, node(view, child).path("round").asInt());
        assertEquals(0, node(view, parent(type)).path("round").asInt());
        assertEquals(row.getId().longValue(), view.path("reworks").get(0).path("id").asLong());
        assertEquals(2, view.path("reworks").get(0).path("quantity").asInt());
    }

    @Test
    void nestedReworkAndSplitBranchesRemainOneCompleteComponent() {
        db.update(
                "UPDATE mom_qms.qms_inspection_procedure_handle_record SET handle_num=1 WHERE"
                        + " id=80");
        var first = rework.create("PROCEDURE", 80L);
        long a = rework.completeFromMes(command(first));
        handle(82, 700, 1);
        var second = rework.create("PROCEDURE", 82L);
        long b = rework.completeFromMes(command(second));
        db.update(
                "UPDATE mom_qms.qms_inspection_procedure_task SET"
                        + " status='FINISHED',result='UN_QUALIFIED',handle_num=1,usable_num=0 WHERE"
                        + " id=?",
                a);
        handle(83, a, 1);
        var third = rework.create("PROCEDURE", 83L);
        long c = rework.completeFromMes(command(third));
        var view = view("PROCEDURE", b);
        assertEquals(4, view.path("nodes").size());
        assertEquals(3, view.path("reworks").size());
        assertEquals(2, node(view, c).path("round").asInt());
        assertEquals(1, node(view, b).path("round").asInt());
        assertEquals(700, view.path("rootTaskId").asLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISPATCHING", "DISPATCH_FAILED", "MES_CREATED"})
    void pendingReworkIsVisibleWithoutInventingAChild(String status) {
        var row = create("PROCEDURE");
        db.update(
                "UPDATE mom_qms.qms_rework_order SET status=?,mes_task_code=? WHERE id=?",
                status,
                "MES_CREATED".equals(status) ? row.getMesTaskCode() : null,
                row.getId());
        var view = view("PROCEDURE", 700L);
        assertEquals(1, view.path("nodes").size());
        assertEquals(1, view.path("reworks").size());
        assertTrue(view.path("reworks").get(0).path("childTaskId").isNull());
        assertEquals(status, view.path("reworks").get(0).path("status").asText());
        assertFalse(view.path("firstPassEligible").asBoolean(true));
    }

    @Test
    void unrelatedHistoricalTaskDoesNotBecomeACertifiedOriginalFirstInspection() {
        var view = view("PROCEDURE", 700L);
        assertEquals(1, view.path("nodes").size());
        assertEquals(0, view.path("reworks").size());
        assertEquals("UNVERIFIED", view.path("originQualification").asText());
        assertFalse(view.path("firstPassEligible").asBoolean(true));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "original_mes_task_code",
                "original_mes_produce_task_code",
                "rework_process_code",
                "mat_code",
                "batch_no",
                "quantity",
                "handle_record_id"
            })
    void damagedFrozenSourceCannotBeDisplayedAsAnActualLink(String column) {
        var row = create("PROCEDURE");
        long child = rework.completeFromMes(command(row));
        Object wrong =
                "quantity".equals(column)
                        ? 1
                        : "handle_record_id".equals(column) ? 99999L : "OTHER";
        db.update(
                "UPDATE mom_qms.qms_rework_order SET " + column + "=? WHERE id=?",
                wrong,
                row.getId());
        assertThrows(RuntimeException.class, () -> view("PROCEDURE", child));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "source_request_id",
                "order_no",
                "source_task_code",
                "mat_code",
                "process_code",
                "batch_no",
                "standard_id",
                "num"
            })
    void mismatchedActualChildCannotBeDisplayedAsAReinspection(String column) {
        var row = create("PROCEDURE");
        long child = rework.completeFromMes(command(row));
        Object wrong =
                "num".equals(column)
                        ? 1
                        : "standard_id".equals(column)
                                ? 999L
                                : "source_task_code".equals(column) ? null : "OTHER";
        db.update(
                "UPDATE mom_qms.qms_inspection_procedure_task SET " + column + "=? WHERE id=?",
                wrong,
                child);
        assertThrows(RuntimeException.class, () -> view("PROCEDURE", 700L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PARENT", "CHILD", "HANDLE", "REWORK"})
    void deletedSourceChildDispositionOrIncomingLinkCannotProduceAPartialGraph(String target) {
        var row = create("PROCEDURE");
        long child = rework.completeFromMes(command(row));
        switch (target) {
            case "PARENT" ->
                    db.update(
                            "UPDATE mom_qms.qms_inspection_procedure_task SET del_flag='1' WHERE"
                                    + " id=700");
            case "CHILD" ->
                    db.update(
                            "UPDATE mom_qms.qms_inspection_procedure_task SET del_flag='1' WHERE"
                                    + " id=?",
                            child);
            case "HANDLE" ->
                    db.update(
                            "UPDATE mom_qms.qms_inspection_procedure_handle_record SET del_flag='1'"
                                    + " WHERE id=80");
            case "REWORK" ->
                    db.update(
                            "UPDATE mom_qms.qms_rework_order SET del_flag='1' WHERE id=?",
                            row.getId());
        }
        assertThrows(
                RuntimeException.class,
                () -> view("PROCEDURE", "REWORK".equals(target) ? child : 700L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"task", "handle", "order"})
    void crossFactoryFactsAreNotJoinedIntoTheCurrentTrace(String entity) {
        var row = create("PROCEDURE");
        long child = rework.completeFromMes(command(row));
        if ("task".equals(entity))
            db.update(
                    "UPDATE mom_qms.qms_inspection_procedure_task SET factory_code='F2' WHERE"
                            + " id=700");
        if ("handle".equals(entity))
            db.update(
                    "UPDATE mom_qms.qms_inspection_procedure_handle_record SET factory_code='F2'"
                            + " WHERE id=80");
        if ("order".equals(entity))
            db.update(
                    "UPDATE mom_qms.qms_rework_order SET factory_code='F2' WHERE id=?",
                    row.getId());
        assertThrows(RuntimeException.class, () -> view("PROCEDURE", child));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void exactTypeActionIsRequiredBeforeReadingOtherTypeFacts(String type) {
        when(permissions.hasPermission(
                        "PROCEDURE".equals(type) ? "procedureTask:show" : "productTask:show"))
                .thenReturn(false);
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> view(type, parent(type)));
    }

    @Test
    void actualFactoryReadAuthorizationCannotBeReplacedByAnActionPermission() {
        var history =
                ReflectionTestUtils.getField(
                        ReflectionTestUtils.getField(fixture, "fixture"), "historyFixture");
        ReflectionTestUtils.setField(history, "authorization", R.ok(false));
        assertThrows(RuntimeException.class, () -> view("PROCEDURE", 700L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TENANT", "FACTORY", "SKIP"})
    void wrongActualScopeCannotReadTheSelectedTask(String target) {
        if ("TENANT".equals(target)) TenantContextHolder.setTenantId(2L);
        if ("FACTORY".equals(target)) FactoryContextHolder.setFactoryCode("F2");
        if ("SKIP".equals(target)) TenantContextHolder.setTenantSkip();
        assertThrows(RuntimeException.class, () -> view("PROCEDURE", 700L));
    }

    @Test
    void missingIncomingReworkCannotBeInferredFromAReservedRequestPrefix() {
        db.update(
                "UPDATE mom_qms.qms_inspection_procedure_task SET"
                        + " source_request_id='REWORK-REINSPECTION:MISSING' WHERE id=700");
        assertThrows(RuntimeException.class, () -> view("PROCEDURE", 700L));
    }

    @Test
    void duplicateCoordinationCannotBeHiddenByMapDeduplication() {
        var row = create("PROCEDURE");
        rework.completeFromMes(command(row));
        db.update(
                "INSERT INTO"
                    + " mom_qms.qms_rework_order(id,tenant_id,factory_code,inspection_type,request_id,inspection_task_id,handle_record_id,original_mes_task_code,original_mes_produce_task_code,rework_process_code,mat_code,quantity,status,mes_task_code,mes_completed_quantity,reinspection_task_id)"
                    + " SELECT"
                    + " 999,tenant_id,factory_code,inspection_type,request_id,inspection_task_id,handle_record_id,original_mes_task_code,original_mes_produce_task_code,rework_process_code,mat_code,quantity,status,mes_task_code,mes_completed_quantity,reinspection_task_id"
                    + " FROM mom_qms.qms_rework_order WHERE id=?",
                row.getId());
        assertThrows(RuntimeException.class, () -> view("PROCEDURE", 700L));
    }

    @Test
    void tooManyConnectedPendingOrdersAreRejectedInsteadOfSilentlyTruncated() {
        db.update(
                "UPDATE mom_qms.qms_inspection_procedure_task SET num=129,handle_num=129 WHERE"
                        + " id=700");
        db.execute(
                "INSERT INTO"
                    + " mom_qms.qms_rework_order(id,tenant_id,factory_code,inspection_type,request_id,inspection_task_id,handle_record_id,original_mes_task_code,original_mes_produce_task_code,rework_process_code,mat_code,quantity,status)"
                    + " SELECT"
                    + " x,1,'F1','PROCEDURE','R-'||x,700,x,'MT0','P0','PROC','MAT',1,'DISPATCHING'"
                    + " FROM SYSTEM_RANGE(1001,1129)");
        assertThrows(RuntimeException.class, () -> view("PROCEDURE", 700L));
    }

    @Test
    void readingDoesNotChangeTasksOrCoordinationProgress() {
        var row = create("PROCEDURE");
        var before = db.queryForList("SELECT * FROM mom_qms.qms_rework_order ORDER BY id");
        var tasks =
                db.queryForList("SELECT * FROM mom_qms.qms_inspection_procedure_task ORDER BY id");
        view("PROCEDURE", 700L);
        assertEquals(before, db.queryForList("SELECT * FROM mom_qms.qms_rework_order ORDER BY id"));
        assertEquals(
                tasks,
                db.queryForList("SELECT * FROM mom_qms.qms_inspection_procedure_task ORDER BY id"));
    }

    @Test
    void exactly128RelatedTasksAreCompleteBut129AreRefused() {
        branches(127);
        assertEquals(128, view("PROCEDURE", 700L).path("nodes").size());
        db.update("DELETE FROM mom_qms.qms_rework_order");
        db.update("DELETE FROM mom_qms.qms_inspection_procedure_handle_record WHERE id>=20000");
        db.update("DELETE FROM mom_qms.qms_inspection_procedure_task WHERE id>=10000");
        branches(128);
        assertThrows(RuntimeException.class, () -> view("PROCEDURE", 700L));
    }

    @Test
    void aFullyMatchedDirectedCycleIsRejected() {
        branches(1);
        db.update(
                "UPDATE mom_qms.qms_inspection_procedure_task SET"
                    + " source_request_id='REWORK-REINSPECTION:RW-BACK',order_no='RW-MT-BACK',inspection_num=1"
                    + " WHERE id=700");
        db.update("UPDATE mom_qms.qms_rework_order SET original_mes_task_code='RW-MT-BACK'");
        handle(20002, 10001, 1);
        db.execute(
                "INSERT INTO"
                    + " mom_qms.qms_rework_order(id,tenant_id,factory_code,inspection_type,request_id,inspection_task_id,handle_record_id,original_mes_task_code,original_mes_produce_task_code,rework_process_code,mat_code,quantity,reason,status,mes_task_code,mes_completed_quantity,reinspection_task_id)"
                    + " VALUES(39999,1,'F1','PROCEDURE','RW-BACK',10001,20002,'RW-MT-1','PX-1','PROC','MAT',1,'接线返修','REINSPECTION_PENDING','RW-MT-BACK',1,700)");
        assertThrows(RuntimeException.class, () -> view("PROCEDURE", 700L));
    }

    @Test
    void allFrontiersUseOneReadOnlySnapshotEvenWhenTheChildChangesBetweenQueries() {
        var row = create("PROCEDURE");
        long child = rework.completeFromMes(command(row));
        var change = new ChangeDuringRead(db, child);
        ((MybatisConfiguration) sessions.getConfiguration()).addInterceptor(change);
        var found = assertDoesNotThrow(() -> view("PROCEDURE", 700L));
        assertEquals(2, node(found, child).path("num").asInt());
        assertTrue(change.changed);
        assertTrue(change.readOnly);
        assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ, change.isolation);
        assertEquals(
                20,
                db.queryForObject(
                        "SELECT num FROM mom_qms.qms_inspection_procedure_task WHERE id=?",
                        Integer.class,
                        child));
    }

    @ParameterizedTest
    @ValueSource(strings = {"product", "procedure"})
    void traceHttpRequiresTheExactTaskTypeAction(String type) throws Exception {
        when(permissions.hasPermission(any())).thenReturn(false);
        var web = web();
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
                                            "/inspection/trace/" + type)
                                    .param("taskId", "700"))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .status()
                                    .isForbidden());
        } finally {
            web.close();
        }
    }

    @Test
    void actualHttpPreservesLongTaskReworkAndDispositionIdentifiers() throws Exception {
        var order = create("PROCEDURE");
        long child = rework.completeFromMes(command(order));
        var web = web();
        try {
            var mvc =
                    org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(
                                    web)
                            .build();
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                            "/inspection/trace/procedure")
                                    .param("taskId", Long.toString(child)))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .status()
                                    .isOk())
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .jsonPath("$.data.taskId")
                                    .value(Long.toString(child)))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .jsonPath("$.data.nodes[1].id")
                                    .value(Long.toString(child)))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .jsonPath("$.data.reworks[0].id")
                                    .value(order.getId().toString()))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .jsonPath("$.data.reworks[0].handleRecordId")
                                    .value("80"));
        } finally {
            web.close();
        }
    }

    private org.springframework.web.context.support.GenericWebApplicationContext web() {
        var web = new org.springframework.web.context.support.GenericWebApplicationContext();
        web.setServletContext(new org.springframework.mock.web.MockServletContext());
        org.springframework.context.annotation.AnnotationConfigUtils
                .registerAnnotationConfigProcessors(web);
        web.registerBean(QmsInspectionHistoryTransactionTest.WebConfiguration.class);
        web.registerBean("pms", PermissionService.class, () -> permissions);
        web.registerBean(
                com.kitex.mom.qms.controller.QmsInspectionTraceController.class,
                () -> new com.kitex.mom.qms.controller.QmsInspectionTraceController(reader()));
        web.refresh();
        return web;
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
    static class ChangeDuringRead implements org.apache.ibatis.plugin.Interceptor {
        private final JdbcTemplate db;
        private final long child;
        boolean changed, readOnly;
        Integer isolation;

        ChangeDuringRead(JdbcTemplate db, long child) {
            this.db = db;
            this.child = child;
        }

        public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            Object value = invocation.proceed();
            var statement = (org.apache.ibatis.mapping.MappedStatement) invocation.getArgs()[0];
            if (!changed && statement.getId().endsWith("QmsInspectionTraceMapper.orders")) {
                changed = true;
                readOnly =
                        org.springframework.transaction.support.TransactionSynchronizationManager
                                .isCurrentTransactionReadOnly();
                isolation =
                        org.springframework.transaction.support.TransactionSynchronizationManager
                                .getCurrentTransactionIsolationLevel();
                try (var connection = db.getDataSource().getConnection();
                        var update =
                                connection.prepareStatement(
                                        "UPDATE mom_qms.qms_inspection_procedure_task SET num=20"
                                                + " WHERE id=?")) {
                    update.setLong(1, child);
                    assertEquals(1, update.executeUpdate());
                }
            }
            return value;
        }
    }

    private void branches(int count) {
        db.update(
                "UPDATE mom_qms.qms_inspection_procedure_task SET num=?,handle_num=? WHERE id=700",
                count,
                count);
        db.execute(
                "INSERT INTO"
                    + " mom_qms.qms_inspection_procedure_task(id,tenant_id,factory_code,source_request_id,source_task_code,order_no,mat_code,process_code,standard_id,num,inspection_num,status)"
                    + " SELECT"
                    + " 10000+x,1,'F1','REWORK-REINSPECTION:RW-'||x,'PX-'||x,'RW-MT-'||x,'MAT','PROC',1,1,1,'WAIT'"
                    + " FROM SYSTEM_RANGE(1,"
                        + count
                        + ")");
        db.execute(
                "INSERT INTO"
                    + " mom_qms.qms_inspection_procedure_handle_record(id,tenant_id,factory_code,task_id,handle_num,handle_result,handle_remark)"
                    + " SELECT 20000+x,1,'F1',700,1,'REWORK','接线返修' FROM SYSTEM_RANGE(1,"
                        + count
                        + ")");
        db.execute(
                "INSERT INTO"
                    + " mom_qms.qms_rework_order(id,tenant_id,factory_code,inspection_type,request_id,inspection_task_id,handle_record_id,original_mes_task_code,original_mes_produce_task_code,rework_process_code,mat_code,quantity,reason,status,mes_task_code,mes_completed_quantity,reinspection_task_id)"
                    + " SELECT"
                    + " 30000+x,1,'F1','PROCEDURE','RW-'||x,700,20000+x,'MT0','P0','PROC','MAT',1,'接线返修','REINSPECTION_PENDING','RW-MT-'||x,1,10000+x"
                    + " FROM SYSTEM_RANGE(1,"
                        + count
                        + ")");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aDeletedOutgoingLinkCannotDisappearFromTheParentOrASurvivingBranch(boolean withSibling) {
        db.update(
                "UPDATE mom_qms.qms_inspection_procedure_handle_record SET handle_num=1 WHERE"
                        + " id=80");
        var removed = rework.create("PROCEDURE", 80L);
        long child = rework.completeFromMes(command(removed));
        Long sibling = null;
        if (withSibling) {
            handle(82, 700, 1);
            sibling = rework.completeFromMes(command(rework.create("PROCEDURE", 82L)));
        }
        db.update("UPDATE mom_qms.qms_rework_order SET del_flag='1' WHERE id=?", removed.getId());
        Long surviving = sibling;
        assertAll(
                () -> assertThrows(RuntimeException.class, () -> view("PROCEDURE", 700L)),
                () -> assertThrows(RuntimeException.class, () -> view("PROCEDURE", child)),
                () -> {
                    if (surviving != null)
                        assertThrows(RuntimeException.class, () -> view("PROCEDURE", surviving));
                });
    }

    private QmsReworkOrderEntity create(String type) {
        var inner = ReflectionTestUtils.getField(fixture, "fixture");
        ((com.kitex.mom.qms.entity.QmsQualityStandardEntity)
                        ReflectionTestUtils.getField(inner, "standard"))
                .setInspectionType(type);
        return rework.create(type, "PROCEDURE".equals(type) ? 80L : 81L);
    }

    private long parent(String type) {
        return "PROCEDURE".equals(type) ? 700L : 800L;
    }

    private void handle(long id, long task, int quantity) {
        db.update(
                "INSERT INTO"
                    + " mom_qms.qms_inspection_procedure_handle_record(id,tenant_id,factory_code,task_id,handle_num,handle_result,handle_remark,status)"
                    + " VALUES(?,1,'F1',?,?,'REWORK','接线返修',FALSE)",
                id,
                task,
                quantity);
    }

    private QmsReworkCompletionDTO command(QmsReworkOrderEntity row) {
        var command = new QmsReworkCompletionDTO();
        command.setRequestId(row.getRequestId());
        command.setMesTaskCode(row.getMesTaskCode());
        command.setMesProduceTaskCode("P-" + row.getId());
        command.setCompletedQuantity(row.getQuantity());
        command.setProcessCode("PROC");
        return command;
    }

    private JsonNode node(JsonNode view, long id) {
        for (var node : view.path("nodes")) if (node.path("id").asLong() == id) return node;
        return fail("missing actual node " + id);
    }

    private QmsInspectionTraceService reader() {
        if (trace == null) {
            var config = (MybatisConfiguration) sessions.getConfiguration();
            var mapper = com.kitex.mom.qms.mapper.QmsInspectionTraceMapper.class;
            if (!config.hasMapper(mapper)) config.addMapper(mapper);
            var core =
                    new QmsInspectionTraceService(sessions.getMapper(mapper), access, permissions);
            var proxy = new ProxyFactory(core);
            proxy.setProxyTargetClass(true);
            proxy.addAdvice(
                    new TransactionInterceptor(
                            transactions, new AnnotationTransactionAttributeSource()));
            trace = (QmsInspectionTraceService) proxy.getProxy();
        }
        return trace;
    }

    private JsonNode view(String type, Long id) {
        return json.valueToTree(reader().view(type, id));
    }
}
