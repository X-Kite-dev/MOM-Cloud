package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mes.controller.MesInspectionEventApplicationController;
import com.kitex.mom.mes.mapper.MesSourceQuantityPlanOutboxMapper;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.authentication.Http403ForbiddenEntryPoint;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.GenericWebApplicationContext;

/** 复用真实同库收件/应用/数量夹具；只替换MDM远端授权，MVC方法权限真实生效。 */
class MesInspectionEventWorkbenchQueryTest {
    MesInspectionEventApplicationTransactionTest actual;
    RemoteMdmFactoryAccessService factories;
    GenericWebApplicationContext web;
    MockMvc mvc;
    MesInspectionEventApplicationTransactionTest.MenuPermissions permissions;

    QueryReads reads = new QueryReads();

    @BeforeEach
    void setup() throws Exception {
        actual = new MesInspectionEventApplicationTransactionTest();
        actual.setup();
        actual.receive(actual.first());
        assertTrue(actual.worker.consume(10L));
        actual.session.getConfiguration().addInterceptor(reads);
        actual.authenticate();
        factories = mock(RemoteMdmFactoryAccessService.class);
        when(factories.check(any())).thenReturn(R.ok(true));
        var query =
                actual.proxy(
                        new MesInspectionEventApplicationQuery(
                                actual.states,
                                actual.projection,
                                new MesActualFactoryAccessService(factories),
                                actual.session.getMapper(MesSourceQuantityPlanOutboxMapper.class)));
        permissions = new MesInspectionEventApplicationTransactionTest.MenuPermissions();
        web = new GenericWebApplicationContext();
        web.setServletContext(new MockServletContext());
        AnnotationConfigUtils.registerAnnotationConfigProcessors(web);
        web.registerBean(
                MesInspectionEventApplicationTransactionTest.ApplicationWebConfiguration.class);
        web.registerBean(
                "pms",
                MesInspectionEventApplicationTransactionTest.MenuPermissions.class,
                () -> permissions);
        web.registerBean(
                MesInspectionEventApplicationController.class,
                () -> new MesInspectionEventApplicationController(query));
        web.refresh();
        mvc =
                MockMvcBuilders.webAppContextSetup(web)
                        .addFilters(
                                new ExceptionTranslationFilter(new Http403ForbiddenEntryPoint()))
                        .build();
    }

    @AfterEach
    void clear() {
        if (web != null) web.close();
        if (actual != null) actual.clear();
    }

    @Test
    void overviewReturnsOnlyMetadataAndDoesNotConfuseAppliedCreateWithQualityPass()
            throws Exception {
        mvc.perform(get("/mesInspectionEventApplication/10/overview"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.contractVersion")
                                .value("MES_INSPECTION_EVENT_WORKBENCH_V1"))
                .andExpect(jsonPath("$.data.recordId").value("10"))
                .andExpect(jsonPath("$.data.actorId").value("7"))
                .andExpect(jsonPath("$.data.appliedThrough").value(1))
                .andExpect(jsonPath("$.data.currentGateStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.items[0].eventId").value("9700"))
                .andExpect(jsonPath("$.data.items[0].decisionSnapshot").doesNotExist());
        actual.quantity("quality_pending_num", 5);
        assertEquals(1, actual.countApplications());
    }

    @Test
    void aSingleExplicitApplicationReadReturnsItsBoundDecision() throws Exception {
        mvc.perform(get("/mesInspectionEventApplication/10/applications/9700"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recordId").value("10"))
                .andExpect(jsonPath("$.data.summary.eventId").value("9700"))
                .andExpect(jsonPath("$.data.decision.passed").value(false));
    }

    @Test
    void summaryUsesAnActualMetadataProjectionAndNeverTheLegacyBlobPage() throws Exception {
        mvc.perform(get("/mesInspectionEventApplication/10/overview")).andExpect(status().isOk());
        assertTrue(reads.sql.containsKey("applicationSummariesPage"));
        assertFalse(
                reads.sql
                        .get("applicationSummariesPage")
                        .toLowerCase()
                        .contains("decision_snapshot"));
        assertFalse(reads.sql.containsKey("applicationsPage"));
        assertFalse(
                reads.sql.values().stream()
                        .anyMatch(sql -> sql.toUpperCase().contains("FOR UPDATE")));
        assertEquals(0, actual.remoteCalls);
        actual.quantity("quality_pending_num", 5);
    }

    @Test
    void allWorkbenchEndpointsRequireTheExistingShowMenu() throws Exception {
        permissions.allowed = false;
        reads.sql.clear();
        for (String suffix : java.util.List.of("overview", "applications/9700", "plan-overview"))
            mvc.perform(get("/mesInspectionEventApplication/10/" + suffix))
                    .andExpect(status().isForbidden());
        assertTrue(reads.sql.isEmpty());
    }

    @Test
    void factoryRevocationAndCrossFactoryRecordsCannotReadAnyDetail() {
        when(factories.check(any())).thenReturn(R.ok(false));
        assertThrows(
                Exception.class,
                () -> mvc.perform(get("/mesInspectionEventApplication/10/overview")));
        when(factories.check(any())).thenReturn(R.ok(true));
        com.kitex.common.data.orgFilter.FactoryContextHolder.setFactoryCode("OTHER");
        assertThrows(
                Exception.class,
                () -> mvc.perform(get("/mesInspectionEventApplication/10/applications/9700")));
    }

    @Test
    void aSameFactoryApplicationCannotBeBorrowedByAnotherGenerationRecord() throws Exception {
        actual.seed(20, 800, "LAST");
        assertThrows(
                Exception.class,
                () -> mvc.perform(get("/mesInspectionEventApplication/20/applications/9700")));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"SHA", "ENVELOPE", "ORIGINAL", "UNAPPLIED", "OVERSIZED"})
    void singleApplicationReadRejectsDamagedOrUncommittedEvidence(String corruption)
            throws Exception {
        switch (corruption) {
            case "SHA" ->
                    actual.db.update(
                            "UPDATE mom_mes.mes_inspection_event_application SET"
                                    + " decision_snapshot='{}' WHERE id=9700");
            case "ENVELOPE" -> {
                var raw =
                        actual.db.queryForObject(
                                "SELECT decision_snapshot FROM"
                                        + " mom_mes.mes_inspection_event_application WHERE id=9700",
                                String.class);
                var body =
                        (com.fasterxml.jackson.databind.node.ObjectNode)
                                MesInspectionEventApplicationTransactionTest.JSON.readTree(raw);
                body.put("genRecordId", "20");
                raw = body.toString();
                actual.db.update(
                        "UPDATE mom_mes.mes_inspection_event_application SET"
                                + " decision_snapshot=?,decision_fingerprint=? WHERE id=9700",
                        raw,
                        MesInspectionEventApplicationTransactionTest.hash(raw));
            }
            case "ORIGINAL" ->
                    actual.db.update(
                            "UPDATE mom_mes.mes_inspection_event_inbox SET payload='{}' WHERE"
                                    + " id=9700");
            case "UNAPPLIED" ->
                    actual.db.update(
                            "UPDATE mom_mes.mes_inspection_event_state SET last_sequence=0 WHERE"
                                    + " id=10");
            case "OVERSIZED" -> {
                actual.db.execute(
                        "ALTER TABLE mom_mes.mes_inspection_event_application ALTER COLUMN"
                                + " decision_snapshot TYPE varchar(2097152)");
                var raw = " ".repeat(1048577);
                actual.db.update(
                        "UPDATE mom_mes.mes_inspection_event_application SET"
                                + " decision_snapshot=?,decision_fingerprint=? WHERE id=9700",
                        raw,
                        MesInspectionEventApplicationTransactionTest.hash(raw));
            }
        }
        assertThrows(
                Exception.class,
                () -> mvc.perform(get("/mesInspectionEventApplication/10/applications/9700")));
        assertTrue(reads.sql.get("applicationBounded").contains("1048576"));
    }

    @Test
    void pendingLaterReceiptAndConflictRemainVisibleWithoutPromotingOldPass() throws Exception {
        var pass = actual.result(actual.first(), "QUALIFIED", 5, 5);
        actual.receive(pass);
        mvc.perform(get("/mesInspectionEventApplication/10/overview"))
                .andExpect(jsonPath("$.data.projectionState").value("PENDING"))
                .andExpect(jsonPath("$.data.receivedThrough").value(2))
                .andExpect(jsonPath("$.data.appliedThrough").value(1));
        assertTrue(actual.worker.consume(10L));
        actual.receive(actual.result(pass, "UN_QUALIFIED", 0, 0));
        assertFalse(actual.worker.consume(10L));
        mvc.perform(get("/mesInspectionEventApplication/10/overview"))
                .andExpect(jsonPath("$.data.projectionState").value("CONFLICT"))
                .andExpect(jsonPath("$.data.blockedEventId").value("9702"));
    }

    @Test
    void ordinaryUnmanagedRecordIsExplicitlyLegacy() throws Exception {
        actual.seed(20, 800, "LAST");
        actual.db.update(
                "UPDATE mom_mes.mes_inspection_gen_record SET produce_task_code='P2' WHERE id=20");
        mvc.perform(get("/mesInspectionEventApplication/20/overview"))
                .andExpect(jsonPath("$.data.projectionState").value("LEGACY"))
                .andExpect(jsonPath("$.data.currentGateStatus").doesNotExist())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void metadataPaginationIsBoundedAndContiguous() throws Exception {
        actual.receive(actual.result(actual.first(), "QUALIFIED", 5, 5));
        assertTrue(actual.worker.consume(10L));
        mvc.perform(get("/mesInspectionEventApplication/10/overview").param("limit", "1"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.nextAfter").value(1))
                .andExpect(jsonPath("$.data.hasMore").value(true));
        mvc.perform(
                        get("/mesInspectionEventApplication/10/overview")
                                .param("after", "1")
                                .param("limit", "1"))
                .andExpect(jsonPath("$.data.items[0].eventId").value("9701"))
                .andExpect(jsonPath("$.data.hasMore").value(false));
        for (String[] range :
                java.util.List.of(
                        new String[] {"-1", "20"},
                        new String[] {"5001", "20"},
                        new String[] {"0", "51"},
                        new String[] {"0", "0"}))
            assertThrows(
                    Exception.class,
                    () ->
                            mvc.perform(
                                    get("/mesInspectionEventApplication/10/overview")
                                            .param("after", range[0])
                                            .param("limit", range[1])));
    }

    @Test
    void planNotificationsUseScopeEnvelopeAndAnExactStringCursorWithoutPayload() throws Exception {
        for (long id : java.util.List.of(9007199254740993L, 9007199254740995L))
            actual.db.update(
                    "INSERT INTO"
                        + " mom_mes.mes_source_quantity_plan_outbox(id,tenant_id,factory_code,task_id,task_code,request_id,status,attempt_count,created_at)"
                        + " VALUES(?,1,'F1',1,'T1',?,'PENDING',0,CURRENT_TIMESTAMP)",
                    id,
                    "REQUEST:" + id);
        mvc.perform(get("/mesInspectionEventApplication/10/plan-overview").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recordId").value("10"))
                .andExpect(jsonPath("$.data.nextAfter").isString())
                .andExpect(jsonPath("$.data.nextAfter").value("9007199254740993"))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.items[0].payload").doesNotExist());
        mvc.perform(
                        get("/mesInspectionEventApplication/10/plan-overview")
                                .param("limit", "1")
                                .param("after", "9007199254740993"))
                .andExpect(jsonPath("$.data.items[0].id").value("9007199254740995"))
                .andExpect(jsonPath("$.data.hasMore").value(false));
        assertFalse(reads.sql.get("page").toLowerCase().contains("payload"));
    }

    @Test
    void nestedParticipantIdsUseTextWithoutRewritingThePermanentSnapshot() throws Exception {
        long record = 9007199254740993L, inspection = 9007199254740995L, event = 9007199254740997L;
        actual.seed(record, inspection, "LAST");
        actual.rule.setInspectionTypes("FIRST,LAST");
        actual.db.update(
                "UPDATE mom_mes.mes_inspection_gen_record SET inspection_result='QUALIFIED' WHERE"
                        + " id=?",
                record);
        actual.receive(actual.bodies.get(inspection));
        var result = actual.result(actual.first(), "QUALIFIED", 5, 5);
        result.put("eventId", Long.toString(event));
        actual.receive(result);
        assertTrue(actual.worker.consume(10L));
        String original =
                actual.db.queryForObject(
                        "SELECT decision_snapshot FROM mom_mes.mes_inspection_event_application"
                                + " WHERE id=?",
                        String.class,
                        event);
        // 可空字段的读取协议夹具：模拟已保存原件明确携带阻断ID，不从当前状态补写旧快照。
        var originalBody =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        MesInspectionEventApplicationTransactionTest.JSON.readTree(original);
        for (var item : originalBody.path("decision").path("items"))
            if ("LAST".equals(item.path("inspectionType").asText()))
                ((com.fasterxml.jackson.databind.node.ObjectNode) item)
                        .put(
                                "qualityBlockedEventId",
                                Long.parseLong(
                                        actual.bodies.get(inspection).path("eventId").asText()));
        original = originalBody.toString();
        actual.db.update(
                "UPDATE mom_mes.mes_inspection_event_application SET"
                        + " decision_snapshot=?,decision_fingerprint=? WHERE id=?",
                original,
                MesInspectionEventApplicationTransactionTest.hash(original),
                event);
        var response =
                mvc.perform(get("/mesInspectionEventApplication/10/applications/" + event))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        saveResponse("nested-id-response.json", response);
        var body = MesInspectionEventApplicationTransactionTest.JSON.readTree(response);
        boolean last = false, first = false;
        for (var item : body.path("data").path("decision").path("items")) {
            if ("LAST".equals(item.path("inspectionType").asText())) {
                assertTrue(item.path("recordId").isTextual());
                assertEquals(Long.toString(record), item.path("recordId").asText());
                assertTrue(item.path("inspectionId").isTextual());
                assertEquals(Long.toString(inspection), item.path("inspectionId").asText());
                assertTrue(item.path("qualityBlockedEventId").isTextual());
                assertEquals(
                        actual.bodies.get(inspection).path("eventId").asText(),
                        item.path("qualityBlockedEventId").asText());
                last = true;
            } else if ("FIRST".equals(item.path("inspectionType").asText())) {
                assertTrue(item.path("qualityEventId").isTextual());
                assertEquals(Long.toString(event), item.path("qualityEventId").asText());
                first = true;
            }
        }
        assertTrue(first && last);
        assertEquals(
                original,
                actual.db.queryForObject(
                        "SELECT decision_snapshot FROM mom_mes.mes_inspection_event_application"
                                + " WHERE id=?",
                        String.class,
                        event));
    }

    @Test
    void decimalReadProjectionPreservesTheOriginalNumericValueWithoutChangingItsProof()
            throws Exception {
        String raw =
                actual.db.queryForObject(
                        "SELECT decision_snapshot FROM mom_mes.mes_inspection_event_application"
                                + " WHERE id=9700",
                        String.class);
        String changed =
                raw.replaceFirst(
                        "\"pendingQuantity\":-?[0-9]+(?:\\.[0-9]+)?",
                        "\"pendingQuantity\":0.100000000000000000003");
        assertNotEquals(raw, changed);
        actual.db.update(
                "UPDATE mom_mes.mes_inspection_event_application SET"
                        + " decision_snapshot=?,decision_fingerprint=? WHERE id=9700",
                changed,
                MesInspectionEventApplicationTransactionTest.hash(changed));
        var response =
                mvc.perform(get("/mesInspectionEventApplication/10/applications/9700"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        saveResponse("decimal-response.json", response);
        var body = MesInspectionEventApplicationTransactionTest.JSON.readTree(response);
        assertTrue(body.path("data").path("decision").path("pendingQuantity").isTextual());
        assertEquals(
                "0.100000000000000000003",
                body.path("data").path("decision").path("pendingQuantity").asText());
        assertEquals(
                changed,
                actual.db.queryForObject(
                        "SELECT decision_snapshot FROM mom_mes.mes_inspection_event_application"
                                + " WHERE id=9700",
                        String.class));
    }

    private void saveResponse(String name, String response) throws Exception {
        String folder = System.getProperty("mom.workbench.read-response-dir");
        if (folder != null) {
            var directory = java.nio.file.Path.of(folder);
            java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.writeString(directory.resolve(name), response);
        }
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
    static class QueryReads implements org.apache.ibatis.plugin.Interceptor {
        java.util.Map<String, String> sql = new java.util.LinkedHashMap<>();

        public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            var statement = (org.apache.ibatis.mapping.MappedStatement) invocation.getArgs()[0];
            sql.put(
                    statement.getId().substring(statement.getId().lastIndexOf('.') + 1),
                    statement.getBoundSql(invocation.getArgs()[1]).getSql());
            return invocation.proceed();
        }
    }
}
