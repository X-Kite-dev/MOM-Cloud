package com.kitex.mom.qms.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.qms.dto.*;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.*;
import com.kitex.mom.qms.service.InspectionProcedureHistoryService;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

/** 真实QMS任务/事件/发件与修订表；审核只是意见原件，不伪造已执行。 */
public class QmsInspectionRevisionTransactionTest {
    QmsInspectionEventOutboxTransactionTest f;
    QmsInspectionRevisionService service;
    QmsInspectionRevisionMapper mapper;
    final InsertFault fault = new InsertFault();
    boolean allowed = true;

    @BeforeEach
    void setup() throws Exception {
        setup(false);
    }

    void setup(boolean qualified) throws Exception {
        f = new QmsInspectionEventOutboxTransactionTest();
        f.setup();
        f.db.execute("CREATE TABLE qms_revision_installation(version int)");
        f.db.update("INSERT INTO qms_revision_installation VALUES(1)");
        f.db.execute(
                "CREATE ALIAS mom_qms.mom_inspection_revision_ready_v1 FOR"
                    + " 'com.kitex.mom.qms.service.impl.QmsInspectionRevisionTransactionTest.ready'");
        f.db.execute(
                "CREATE TABLE mom_qms.qms_inspection_revision(id bigint PRIMARY KEY,tenant_id"
                    + " bigint NOT NULL,factory_code varchar NOT NULL,task_id bigint NOT"
                    + " NULL,base_event_id bigint NOT NULL,base_sequence bigint NOT"
                    + " NULL,base_fingerprint varchar NOT NULL,requested_by bigint NOT"
                    + " NULL,requested_at timestamp NOT NULL,target_result varchar NOT"
                    + " NULL,target_usable int NOT NULL,target_handled int NOT NULL,reason varchar"
                    + " NOT NULL,payload text NOT NULL,fingerprint varchar NOT NULL)");
        f.db.execute(
                "CREATE TABLE mom_qms.qms_inspection_revision_review(id bigint PRIMARY"
                    + " KEY,tenant_id bigint NOT NULL,factory_code varchar NOT NULL,task_id bigint"
                    + " NOT NULL,request_fingerprint varchar NOT NULL,reviewed_by bigint NOT"
                    + " NULL,reviewed_at timestamp NOT NULL,decision varchar NOT NULL,note varchar"
                    + " NOT NULL)");
        var c = f.session.getConfiguration();
        c.addInterceptor(fault);
        for (var type :
                List.of(QmsInspectionRevisionEntity.class, QmsInspectionRevisionReviewEntity.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(c, "revision"), type);
        c.addMapper(QmsInspectionRevisionMapper.class);
        c.addMapper(QmsInspectionRevisionReviewMapper.class);
        mapper = f.session.getMapper(QmsInspectionRevisionMapper.class);
        service =
                f.proxy(
                        new QmsInspectionRevisionService(
                                mapper,
                                f.session.getMapper(QmsInspectionRevisionReviewMapper.class),
                                f.session.getMapper(QmsInspectionEventMapper.class),
                                f.journal,
                                (QmsInspectionHistorySupport)
                                        f.deps.get(QmsInspectionHistorySupport.class)));
        var remote =
                (RemoteMdmFactoryAccessService) f.deps.get(RemoteMdmFactoryAccessService.class);
        doAnswer(a -> R.ok(allowed)).when(remote).check(any());
        f.db.update(
                "UPDATE mom_qms.qms_inspection_procedure_task SET"
                        + " result=NULL,status='WAIT',usable_num=NULL,handle_num=NULL");
        var history =
                (InspectionProcedureHistoryService)
                        ReflectionTestUtils.getField(f.origin, "procedure");
        var tasks = f.session.getMapper(QmsInspectionProcedureTaskMapper.class);
        history.create(tasks.selectById(700L));
        f.db.update(
                "UPDATE mom_qms.qms_inspection_procedure_task SET"
                        + " result='UN_QUALIFIED',status='UNHANDLED',usable_num=0,handle_num=0");
        if (qualified)
            f.db.update(
                    "UPDATE mom_qms.qms_inspection_procedure_task SET"
                            + " result='QUALIFIED',status='FINISHED',usable_num=5,handle_num=5");
        QmsInspectionRevisionCoordinationTestSupport.install(f.session, f.db);
        history.check(tasks.selectById(700L));
    }

    @AfterEach
    void clear() {
        if (f != null) f.clear();
    }

    public static Integer ready(java.sql.Connection c) throws Exception {
        try (var s = c.createStatement();
                var r = s.executeQuery("SELECT version FROM qms_revision_installation")) {
            return r.next() ? r.getInt(1) : null;
        }
    }

    QmsInspectionRevisionApply apply() {
        var b = service.basis(700L);
        return new QmsInspectionRevisionApply(
                700L, Long.valueOf(b.eventId()), b.fingerprint(), "QUALIFIED", 5, 5, "复核量具后申请修订");
    }

    QmsInspectionRevisionEntity request() {
        return service.apply(apply());
    }

    void actor(long id) {
        var u = mock(MomUser.class);
        when(u.getId()).thenReturn(id);
        when(u.getTenantId()).thenReturn(1L);
        when(u.getUsername()).thenReturn("reviewer" + id);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(u, "N/A", List.of()));
    }

    QmsInspectionRevisionReview review(String decision) {
        return new QmsInspectionRevisionReview(decision, "复核检验记录及量具结果");
    }

    int count(String table) {
        return f.db.queryForObject("SELECT COUNT(*) FROM mom_qms." + table, Integer.class);
    }

    @Test
    void applicationFreezesExactHeadAndNullsWithoutChangingQualityFacts() throws Exception {
        var before = f.db.queryForList("SELECT * FROM mom_qms.qms_inspection_procedure_task");
        var r = request();
        assertEquals(2L, r.getRequestedBy());
        assertEquals(2L, r.getBaseSequence());
        var raw = new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.getPayload());
        assertTrue(raw.path("source").get("matBarcode").isNull());
        assertEquals("UN_QUALIFIED", raw.path("source").path("result").textValue());
        assertEquals(r.getFingerprint(), QmsInspectionEventDeliveryCodec.hash(r.getPayload()));
        assertEquals(
                before, f.db.queryForList("SELECT * FROM mom_qms.qms_inspection_procedure_task"));
        assertEquals(2, count("qms_inspection_event"));
        assertEquals(2, count("qms_inspection_event_outbox"));
    }

    @Test
    void independentApprovalIsPermanentReviewAndStillPendingExecution() {
        var r = request();
        actor(3);
        var d = service.review(700L, r.getId(), review("APPROVED"));
        assertEquals(3L, d.getReviewedBy());
        assertEquals("APPROVED", d.getDecision());
        assertEquals("UN_QUALIFIED", service.basis(700L).source().path("result").textValue());
        assertEquals(2, count("qms_inspection_event"));
        assertEquals("PENDING_EXECUTION", service.detail(700L, r.getId()).state());
    }

    @Test
    void applicantCannotSelfReview() {
        var r = request();
        assertThrows(
                RuntimeException.class, () -> service.review(700L, r.getId(), review("APPROVED")));
        assertEquals(0, count("qms_inspection_revision_review"));
    }

    @Test
    void secondPendingRequestIsRejected() {
        request();
        assertThrows(RuntimeException.class, this::request);
        assertEquals(1, count("qms_inspection_revision"));
    }

    @Test
    void rejectedApplicationAllowsNewRequestButReviewCannotBeOverwritten() {
        var r = request();
        actor(3);
        service.review(700L, r.getId(), review("REJECTED"));
        assertThrows(
                RuntimeException.class, () -> service.review(700L, r.getId(), review("APPROVED")));
        actor(2);
        request();
        assertEquals(2, count("qms_inspection_revision"));
    }

    @Test
    void approvedPendingExecutionBlocksAnotherApplication() {
        var r = request();
        actor(3);
        service.review(700L, r.getId(), review("APPROVED"));
        actor(2);
        assertThrows(RuntimeException.class, this::request);
    }

    @Test
    void wrongHeadCannotBeUsed() {
        var a = apply();
        assertThrows(
                RuntimeException.class,
                () ->
                        service.apply(
                                new QmsInspectionRevisionApply(
                                        700L,
                                        a.baseEventId(),
                                        "f".repeat(64),
                                        a.result(),
                                        5,
                                        5,
                                        a.reason())));
        assertEquals(0, count("qms_inspection_revision"));
    }

    @Test
    void changedCurrentSourceInvalidatesReview() {
        var r = request();
        f.db.update("UPDATE mom_qms.qms_inspection_procedure_task SET usable_num=1");
        actor(3);
        assertThrows(
                RuntimeException.class, () -> service.review(700L, r.getId(), review("APPROVED")));
        assertEquals(0, count("qms_inspection_revision_review"));
    }

    @Test
    void realLaterEventInvalidatesReview() {
        var r = request();
        var h =
                (InspectionProcedureHistoryService)
                        ReflectionTestUtils.getField(f.origin, "procedure");
        h.check(f.session.getMapper(QmsInspectionProcedureTaskMapper.class).selectById(700L));
        actor(3);
        assertThrows(
                RuntimeException.class, () -> service.review(700L, r.getId(), review("APPROVED")));
    }

    @Test
    void legacySourceCannotApply() {
        var command = apply();
        f.db.update("UPDATE mom_qms.qms_inspection_procedure_task SET source_request_id=NULL");
        assertThrows(RuntimeException.class, () -> service.apply(command));
    }

    @Test
    void foreignFactoryCannotReadOrReview() {
        var r = request();
        FactoryContextHolder.setFactoryCode("OTHER");
        assertThrows(RuntimeException.class, () -> service.detail(700L, r.getId()));
        assertThrows(RuntimeException.class, () -> service.basis(700L));
    }

    @Test
    void denialCannotReadOrWrite() {
        var a = apply();
        allowed = false;
        assertThrows(RuntimeException.class, () -> service.apply(a));
        assertThrows(RuntimeException.class, () -> service.basis(700L));
        assertThrows(RuntimeException.class, () -> service.page(700L, 0L, 20));
    }

    @Test
    void skippedTenantOrAnonymousCannotApply() {
        var a = apply();
        TenantContextHolder.setTenantSkip();
        assertThrows(RuntimeException.class, () -> service.apply(a));
        TenantContextHolder.clear();
        TenantContextHolder.setTenantId(1L);
        SecurityContextHolder.clearContext();
        assertThrows(RuntimeException.class, () -> service.apply(a));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "EXEMPTION", "CANCELED", "QUALIFIED ", "unqualified"})
    void invalidTargetIsRejected(String result) {
        var a = apply();
        assertThrows(
                RuntimeException.class,
                () ->
                        service.apply(
                                new QmsInspectionRevisionApply(
                                        700L,
                                        a.baseEventId(),
                                        a.baseFingerprint(),
                                        result,
                                        5,
                                        5,
                                        a.reason())));
    }

    @Test
    void targetsMustUseExplicitWholeLotCounts() {
        var a = apply();
        for (Integer n : Arrays.asList(null, -1, 0, 4, 6))
            assertThrows(
                    RuntimeException.class,
                    () ->
                            service.apply(
                                    new QmsInspectionRevisionApply(
                                            700L,
                                            a.baseEventId(),
                                            a.baseFingerprint(),
                                            a.result(),
                                            n,
                                            5,
                                            a.reason())));
    }

    @Test
    void unchangedVerdictIsNotARevision() {
        var a = apply();
        assertThrows(
                RuntimeException.class,
                () ->
                        service.apply(
                                new QmsInspectionRevisionApply(
                                        700L,
                                        a.baseEventId(),
                                        a.baseFingerprint(),
                                        "UN_QUALIFIED",
                                        0,
                                        0,
                                        a.reason())));
    }

    @Test
    void reasonAndReviewNoteMustBeMeaningfulAndBounded() {
        var a = apply();
        for (String s : List.of(" ", "x".repeat(1001)))
            assertThrows(
                    RuntimeException.class,
                    () ->
                            service.apply(
                                    new QmsInspectionRevisionApply(
                                            700L,
                                            a.baseEventId(),
                                            a.baseFingerprint(),
                                            a.result(),
                                            5,
                                            5,
                                            s)));
        var r = request();
        actor(3);
        assertThrows(
                RuntimeException.class,
                () ->
                        service.review(
                                700L, r.getId(), new QmsInspectionRevisionReview("APPROVED", " ")));
    }

    @Test
    void requestPayloadTamperingBlocksReadAndReview() {
        var r = request();
        f.db.update("UPDATE mom_qms.qms_inspection_revision SET payload=payload||' '");
        assertThrows(RuntimeException.class, () -> service.detail(700L, r.getId()));
        actor(3);
        assertThrows(
                RuntimeException.class, () -> service.review(700L, r.getId(), review("APPROVED")));
    }

    @Test
    void paginationIsTaskBounded() {
        request();
        assertEquals(1, service.page(700L, 0L, 1).items().size());
        assertThrows(RuntimeException.class, () -> service.page(700L, 0L, 51));
        assertThrows(RuntimeException.class, () -> service.page(700L, -1L, 20));
    }

    @Test
    void missingInstallationBlocksAllPorts() {
        f.db.update("UPDATE qms_revision_installation SET version=0");
        assertThrows(RuntimeException.class, () -> service.basis(700L));
        assertThrows(RuntimeException.class, () -> service.page(700L, 0L, 20));
        assertEquals(0, count("qms_inspection_revision"));
    }

    @Test
    void failedRequestInsertRollsBackActualInsertedRow() {
        var cmd = apply();
        fault.table = "QmsInspectionRevisionMapper";
        assertThrows(RuntimeException.class, () -> service.apply(cmd));
        assertEquals(0, count("qms_inspection_revision"));
        assertEquals(2, count("qms_inspection_event"));
    }

    @Test
    void failedReviewInsertRollsBackActualInsertedDecision() {
        var r = request();
        actor(3);
        fault.table = "QmsInspectionRevisionReviewMapper";
        assertThrows(
                RuntimeException.class, () -> service.review(700L, r.getId(), review("APPROVED")));
        assertEquals(0, count("qms_inspection_revision_review"));
        assertEquals("PENDING_REVIEW", service.detail(700L, r.getId()).state());
    }

    @Test
    void staleApplicationCanBeRejectedToAllowFreshBasis() {
        var r = request();
        f.db.update("UPDATE mom_qms.qms_inspection_procedure_task SET usable_num=1");
        actor(3);
        service.review(700L, r.getId(), review("REJECTED"));
        assertEquals("REJECTED", service.detail(700L, r.getId()).state());
    }

    @Test
    void sameTenantWrongTaskCannotReadOrReview() {
        var r = request();
        assertThrows(RuntimeException.class, () -> service.detail(701L, r.getId()));
        actor(3);
        assertThrows(
                RuntimeException.class, () -> service.review(701L, r.getId(), review("APPROVED")));
    }

    @Test
    void simultaneousRequestsHaveOnePendingWinner() throws Exception {
        var cmd = apply();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Boolean> action =
                    () -> {
                        TenantContextHolder.setTenantId(1L);
                        FactoryContextHolder.setFactoryCode("F1");
                        actor(2);
                        try {
                            start.await();
                            service.apply(cmd);
                            return true;
                        } catch (RuntimeException e) {
                            return false;
                        } finally {
                            TenantContextHolder.clear();
                            FactoryContextHolder.clear();
                            SecurityContextHolder.clearContext();
                        }
                    };
            var a = pool.submit(action);
            var b = pool.submit(action);
            start.countDown();
            assertNotEquals(
                    a.get(5, java.util.concurrent.TimeUnit.SECONDS),
                    b.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, count("qms_inspection_revision"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void mvcSeparatesReadApplyAndReviewAndSerializesIds() throws Exception {
        var web = new org.springframework.web.context.support.GenericWebApplicationContext();
        web.setServletContext(new org.springframework.mock.web.MockServletContext());
        org.springframework.context.annotation.AnnotationConfigUtils
                .registerAnnotationConfigProcessors(web);
        web.registerBean(QmsInspectionHistoryTransactionTest.WebConfiguration.class);
        var permissions = new Permissions();
        web.registerBean("pms", Permissions.class, () -> permissions);
        web.registerBean(
                com.kitex.mom.qms.controller.QmsInspectionRevisionController.class,
                () -> new com.kitex.mom.qms.controller.QmsInspectionRevisionController(service));
        web.refresh();
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
            String base = "/inspection/procedure/revisions";
            permissions.allowed.add("procedureTask:history");
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                    base + "/700/basis"))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .status()
                                    .isOk())
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .jsonPath("$.data.taskId")
                                    .value("700"))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .jsonPath("$.data.actorId")
                                    .value("2"));
            var json =
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(apply());
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .post(base)
                                    .contentType("application/json")
                                    .content(json))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .status()
                                    .isForbidden());
            permissions.allowed.add("procedureTask:revision:apply");
            var response =
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.post(base)
                                            .contentType("application/json")
                                            .content(json))
                            .andExpect(
                                    org.springframework.test.web.servlet.result
                                            .MockMvcResultMatchers.status()
                                            .isOk())
                            .andReturn();
            var parsed =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(response.getResponse().getContentAsString());
            assertTrue(parsed.path("data").path("id").isTextual());
            String id = parsed.path("data").path("id").textValue();
            actor(3);
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .post(base + "/700/" + id + "/review")
                                    .contentType("application/json")
                                    .content(
                                            "{\"decision\":\"APPROVED\",\"note\":\"independent"
                                                    + " review\"}"))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .status()
                                    .isForbidden());
            permissions.allowed.clear();
            permissions.allowed.add("procedureTask:revision:review");
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                    base + "/700"))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .status()
                                    .isForbidden());
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .post(base + "/700/" + id + "/review")
                                    .contentType("application/json")
                                    .content(
                                            "{\"decision\":\"APPROVED\",\"note\":\"independent"
                                                    + " review\"}"))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .status()
                                    .isOk());
        } finally {
            web.close();
        }
    }

    public static class Permissions {
        final Set<String> allowed = new HashSet<>();

        public boolean hasPermission(String p) {
            return allowed.contains(p);
        }
    }

    @org.apache.ibatis.plugin.Intercepts(
            @org.apache.ibatis.plugin.Signature(
                    type = org.apache.ibatis.executor.Executor.class,
                    method = "update",
                    args = {org.apache.ibatis.mapping.MappedStatement.class, Object.class}))
    static class InsertFault implements org.apache.ibatis.plugin.Interceptor {
        String table = "";

        public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            var ms = (org.apache.ibatis.mapping.MappedStatement) invocation.getArgs()[0];
            var result = invocation.proceed();
            return !table.isEmpty() && ms.getId().contains(table + ".insert") ? 0 : result;
        }
    }

    @Test
    void unavailableCurrentBasisStillAllowsReadingAndRejectingStaleRequest() {
        var r = request();
        f.db.update("UPDATE mom_qms.qms_inspection_procedure_task SET usable_num=1");
        assertFalse(
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .valueToTree(service.basis(700L))
                        .path("available")
                        .asBoolean(true));
        assertEquals(1, service.page(700L, 0L, 20).items().size());
        actor(3);
        service.review(700L, r.getId(), review("REJECTED"));
        assertEquals("REJECTED", service.detail(700L, r.getId()).state());
    }

    @Test
    void missingNullableSourceFieldIsNotACompleteRevisionBasis() throws Exception {
        var e = f.journal.originals("PROCEDURE", 700L).get(1);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var raw = json.readTree(e.getPayload());
        ((com.fasterxml.jackson.databind.node.ObjectNode) raw.get("source")).remove("matBarcode");
        String payload = json.writeValueAsString(raw);
        f.db.update(
                "UPDATE mom_qms.qms_inspection_event SET payload=?,fingerprint=? WHERE id=?",
                payload,
                QmsInspectionEventDeliveryCodec.hash(payload),
                e.getId());
        assertThrows(RuntimeException.class, () -> service.basis(700L));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "RAW,PAGE",
        "RAW,DETAIL",
        "RAW,REJECT",
        "CHAIN,PAGE",
        "CHAIN,DETAIL",
        "CHAIN,REJECT",
        "MISSING,PAGE",
        "MISSING,DETAIL",
        "MISSING,REJECT"
    })
    void corruptReferencedEventsCannotBeReadOrRejected(String damage, String port) {
        var r = request();
        switch (damage) {
            case "RAW" ->
                    f.db.update(
                            "UPDATE mom_qms.qms_inspection_event SET payload=payload||' ' WHERE"
                                    + " id=?",
                            r.getBaseEventId());
            case "CHAIN" ->
                    f.db.update("DELETE FROM mom_qms.qms_inspection_event WHERE sequence_no=1");
            case "MISSING" ->
                    f.db.update(
                            "DELETE FROM mom_qms.qms_inspection_event WHERE id=?",
                            r.getBaseEventId());
            default -> fail(damage);
        }
        actor(3);
        assertThrows(
                RuntimeException.class,
                () -> {
                    switch (port) {
                        case "PAGE" -> service.page(700L, 0L, 20);
                        case "DETAIL" -> service.detail(700L, r.getId());
                        case "REJECT" -> service.review(700L, r.getId(), review("REJECTED"));
                        default -> fail(port);
                    }
                });
        assertEquals(0, count("qms_inspection_revision_review"));
    }

    @Test
    void anIntactHistoricalBaseRemainsReadableAndRejectableAfterALaterResult() {
        var r = request();
        var h =
                (InspectionProcedureHistoryService)
                        ReflectionTestUtils.getField(f.origin, "procedure");
        h.check(f.session.getMapper(QmsInspectionProcedureTaskMapper.class).selectById(700L));
        assertEquals(1, service.page(700L, 0L, 20).items().size());
        assertEquals("PENDING_REVIEW", service.detail(700L, r.getId()).state());
        actor(3);
        service.review(700L, r.getId(), review("REJECTED"));
        assertEquals("REJECTED", service.detail(700L, r.getId()).state());
    }
}
