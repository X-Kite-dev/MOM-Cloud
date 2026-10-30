package com.kitex.mom.plan.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.api.feign.RemoteMesPlanCompletionReopenService;
import com.kitex.mom.mes.dto.MesPlanCompletionReopenEvidence;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.impl.PlnCompletionReopenEventHandler;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import javax.sql.DataSource;

/** PLAN反向事实应用、窄状态更新和永久回执的实际持久化事务。 */
class PlnCompletionReopenTransactionTest {
    private final Map<Long, MesPlanCompletionReopenEvidence> sourceProofs = new HashMap<>();
    private boolean hidePlansFromInteractiveScope;
    private boolean hideCallbacksFromInteractiveScope;
    private JdbcTemplate db;
    private TransactionTemplate tx;
    private ObjectMapper json;
    private PlnCompletionReopenEventHandler handler;
    private MesPlanCompletionReopenEvidence proof;
    private PlnAutomationEventEntity event;
    private final Map<Class<?>, Object> dependencies = new HashMap<>();

    @BeforeEach
    void setup() throws Exception {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:pln-reopen-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        source.setDriverClassName("org.h2.Driver");
        db = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        dependencies.put(DataSource.class, source);
        var config = new MybatisConfiguration();
        var global = new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(
                new com.kitex.common.data.mybatis.MybatisPlusMetaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(config, global);
        for (var m :
                List.of(
                        PlnIssueMapper.class,
                        PlnCompletionReopenReceiptMapper.class,
                        PlnIssueCallbackReceiptMapper.class,
                        PlnMesCompletionReceiptMapper.class,
                        PlnAutomationEventMapper.class,
                        PlnAutomationEventOperationMapper.class,
                        PlnAutomationEventPolicyMapper.class)) config.addMapper(m);
        for (var e :
                List.of(
                        PlnIssueEntity.class,
                        PlnCompletionReopenReceiptEntity.class,
                        PlnIssueCallbackReceiptEntity.class,
                        PlnMesCompletionReceiptEntity.class,
                        PlnAutomationEventEntity.class,
                        PlnAutomationEventOperationEntity.class,
                        PlnAutomationEventPolicyEntity.class)) table(config, e);
        db.execute("CREATE SCHEMA mom_plan");
        for (String name :
                List.of(
                        "pln_issue",
                        "pln_completion_reopen_receipt",
                        "pln_issue_callback_receipt",
                        "pln_mes_completion_receipt",
                        "pln_automation_event",
                        "pln_automation_event_operation",
                        "pln_automation_event_policy"))
            db.execute("CREATE SYNONYM mom_plan." + name + " FOR PUBLIC." + name);
        var plugins = new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
        plugins.addInnerInterceptor(
                new com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor(
                        new com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler() {
                            @Override
                            public net.sf.jsqlparser.expression.Expression getTenantId() {
                                return new net.sf.jsqlparser.expression.LongValue(
                                        TenantContextHolder.getTenantId());
                            }
                        }));
        plugins.addInnerInterceptor(
                new com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor(
                        new com.baomidou.mybatisplus.extension.plugins.handler
                                .MultiDataPermissionHandler() {
                            @Override
                            public net.sf.jsqlparser.expression.Expression getSqlSegment(
                                    net.sf.jsqlparser.schema.Table table,
                                    net.sf.jsqlparser.expression.Expression where,
                                    String statement) {
                                if ((hidePlansFromInteractiveScope
                                                && "pln_issue".equalsIgnoreCase(table.getName()))
                                        || (hideCallbacksFromInteractiveScope
                                                && "pln_issue_callback_receipt"
                                                        .equalsIgnoreCase(table.getName()))) {
                                    var denied =
                                            new net.sf.jsqlparser.expression.operators.relational
                                                    .EqualsTo();
                                    denied.setLeftExpression(
                                            new net.sf.jsqlparser.expression.LongValue(1));
                                    denied.setRightExpression(
                                            new net.sf.jsqlparser.expression.LongValue(0));
                                    return denied;
                                }
                                return null;
                            }
                        }));
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setConfiguration(config);
        factory.setGlobalConfig(global);
        factory.setDataSource(source);
        factory.setPlugins(plugins);
        var session = new SqlSessionTemplate(factory.getObject());
        for (var m :
                List.of(
                        PlnIssueMapper.class,
                        PlnCompletionReopenReceiptMapper.class,
                        PlnIssueCallbackReceiptMapper.class,
                        PlnMesCompletionReceiptMapper.class,
                        PlnAutomationEventMapper.class,
                        PlnAutomationEventOperationMapper.class,
                        PlnAutomationEventPolicyMapper.class))
            dependencies.put(m, session.getMapper(m));
        db.execute("CREATE TABLE receive_lock(id int primary key)");
        db.update("INSERT INTO receive_lock VALUES(1)");
        var originalEvents = dependency(PlnAutomationEventMapper.class);
        dependencies.put(
                PlnAutomationEventMapper.class,
                java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {PlnAutomationEventMapper.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("lockKey"))
                                return db.queryForObject(
                                        "SELECT id FROM receive_lock WHERE id=1 FOR UPDATE",
                                        Integer.class);
                            try {
                                return method.invoke(originalEvents, args);
                            } catch (java.lang.reflect.InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        }));
        json = platformJson();
        dependencies.put(ObjectMapper.class, json);
        var remote = mock(RemoteMesPlanCompletionReopenService.class);
        when(remote.evidence(anyLong(), anyString()))
                .thenAnswer(
                        call -> {
                            var actual = sourceProofs.getOrDefault(call.getArgument(0), proof);
                            assertEquals(
                                    actual.getFactoryCode(), FactoryContextHolder.getFactoryCode());
                            assertEquals(actual.getTenantId(), TenantContextHolder.getTenantId());
                            return R.ok(actual);
                        });
        dependencies.put(RemoteMesPlanCompletionReopenService.class, remote);
        var c = PlnCompletionReopenEventHandler.class.getDeclaredConstructors()[0];
        handler =
                (PlnCompletionReopenEventHandler)
                        c.newInstance(
                                Arrays.stream(c.getParameterTypes())
                                        .map(t -> dependencies.computeIfAbsent(t, k -> mock(t)))
                                        .toArray());
        proof =
                new MesPlanCompletionReopenEvidence()
                        .setTenantId(1L)
                        .setFactoryCode("F1")
                        .setOutboxId(80L)
                        .setEventId("MES-COMPLETION-REOPEN:90:V1")
                        .setReverseRequestId(90L)
                        .setTaskId(11L)
                        .setTaskCode("TASK-1")
                        .setPlanNo("PLAN-1")
                        .setProductCode("MAT1")
                        .setCompleteQuantityBefore(new BigDecimal("2"))
                        .setCompleteQuantityAfter(BigDecimal.ONE)
                        .setCompletionVersionBefore(0)
                        .setCompletionVersionAfter(1)
                        .setReopenedTime(LocalDateTime.of(2026, 10, 25, 10, 0))
                        .setPayloadFingerprint("d".repeat(64))
                        .setSourceValid(true)
                        .setSourceLines(
                                List.of(
                                        new MesPlanCompletionReopenEvidence.SourceLine()
                                                .setIssueId(1L)
                                                .setIssueNo("PLAN-1")
                                                .setAllocatedQuantity(new BigDecimal("2"))
                                                .setCompletedQuantity(BigDecimal.ONE)));
        event =
                new PlnAutomationEventEntity()
                        .setEventId(proof.getEventId())
                        .setSourceSystem("MES")
                        .setEventType("MES_COMPLETION_REOPENED")
                        .setEventVersion(1)
                        .setAggregateType("MES_TASK")
                        .setAggregateId("11")
                        .setFactoryCode("F1");
        event.setId(100L);
        event.setTenantId(1L);
        db.update(
                "INSERT INTO"
                    + " pln_issue(id,tenant_id,factory_code,issue_no,product_code,num,status,finish_time,finish_num)"
                    + " VALUES(1,1,'F1','PLAN-1','MAT1',5,'3',DATE '2026-10-24',1)");
    }

    private String payloadRaw(MesPlanCompletionReopenEvidence source) {
        try {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(source);
            node.putNull("payloadFingerprint");
            node.putNull("sourceValid");
            return json.writeValueAsString(node);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private String digest(String raw) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private PlnCompletionReopenEventHandler.ApplyResult apply() {
        try {
            String raw = payloadRaw(proof);
            if (proof.getSourceSchemaVersion() == null || proof.getSourceSchemaVersion() == 1)
                raw = json.writeValueAsString(json.readTree(raw));
            event.setPayloadJson(raw).setPayloadFingerprint(digest(raw));
            var node = json.readTree(raw);
            return tx.execute(status -> handler.apply(event, node));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void approvedCorrectionReopensThePlanAndActuallyClearsItsOldFinishDate() {
        var result = apply();
        assertEquals(1, result.appliedCount());
        assertEquals("2", db.queryForObject("SELECT status FROM pln_issue", String.class));
        assertNull(db.queryForObject("SELECT finish_time FROM pln_issue", LocalDate.class));
        assertEquals(
                0,
                BigDecimal.ONE.compareTo(
                        db.queryForObject("SELECT finish_num FROM pln_issue", BigDecimal.class)));
        assertEquals(
                LocalDate.of(2026, 10, 24),
                db.queryForObject(
                        "SELECT finish_date_before FROM pln_completion_reopen_receipt",
                        LocalDate.class));
    }

    @Test
    void aClosedPlanReceivesAPermanentIgnoredReceiptAndIsNeverReopened() {
        db.update("UPDATE pln_issue SET status='4'");
        var result = assertDoesNotThrow(this::apply);
        assertEquals(0, result.appliedCount());
        assertEquals("4", db.queryForObject("SELECT status FROM pln_issue", String.class));
        assertEquals(
                "CLOSED_IGNORED",
                db.queryForObject(
                        "SELECT apply_result FROM pln_completion_reopen_receipt", String.class));
    }

    private void twoTaskSources() {
        proof.setSourceSchemaVersion(2)
                .setApsBatchNo("BATCH-1")
                .setSourceLines(
                        List.of(
                                new MesPlanCompletionReopenEvidence.SourceLine()
                                        .setIssueId(2L)
                                        .setIssueNo("PLAN-2")
                                        .setAllocatedQuantity(BigDecimal.ONE)
                                        .setCompletedQuantity(BigDecimal.ZERO),
                                new MesPlanCompletionReopenEvidence.SourceLine()
                                        .setIssueId(1L)
                                        .setIssueNo("PLAN-1")
                                        .setAllocatedQuantity(BigDecimal.ONE)
                                        .setCompletedQuantity(BigDecimal.ONE)));
        event.setEventVersion(2);
        proof.setPayloadFingerprint(digest(payloadRaw(proof)));
        db.update(
                "INSERT INTO"
                    + " pln_issue(id,tenant_id,factory_code,issue_no,product_code,num,status,finish_time,finish_num)"
                    + " VALUES(2,1,'F1','PLAN-2','MAT1',1,'3',DATE '2026-10-24',0)");
    }

    @Test
    void aReopenedTaskReopensEverySourceEvenWhenOneRoundedLineIsStillFull() {
        twoTaskSources();
        var result = apply();
        assertEquals(2, result.appliedCount());
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT count(*) FROM pln_issue WHERE status='2' AND finish_time IS NULL",
                        Integer.class));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT count(*) FROM pln_completion_reopen_receipt WHERE"
                                + " source_schema_version=2",
                        Integer.class));
    }

    @Test
    void sourceReceiptFailureRollsBackEverySourceDecision() {
        twoTaskSources();
        db.execute(
                "ALTER TABLE pln_completion_reopen_receipt ADD CONSTRAINT reject_second"
                        + " CHECK(issue_id<>2)");
        assertThrows(RuntimeException.class, this::apply);
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT count(*) FROM pln_issue WHERE status='3' AND finish_time IS NOT"
                                + " NULL",
                        Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM pln_completion_reopen_receipt", Integer.class));
    }

    @Test
    void replayOfAnAppliedCorrectionDoesNotWriteOverALaterPlanClose() {
        var first = apply();
        db.update("UPDATE pln_issue SET status='4'");
        var second = apply();
        assertEquals(first.receipts().get(0).getId(), second.receipts().get(0).getId());
        assertEquals("4", db.queryForObject("SELECT status FROM pln_issue", String.class));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM pln_completion_reopen_receipt", Integer.class));
    }

    @Test
    void changedSourceFingerprintCannotReuseTheOriginalCorrectionReceipt() {
        apply();
        proof.setPayloadFingerprint("e".repeat(64));
        assertThrows(com.kitex.common.core.exception.CheckedException.class, this::apply);
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM pln_completion_reopen_receipt", Integer.class));
    }

    @Test
    void unknownNewSourceModelCannotBeTreatedAsLegacy() {
        proof.setSourceSchemaVersion(3);
        event.setEventVersion(3);
        assertThrows(com.kitex.common.core.exception.CheckedException.class, this::apply);
        assertEquals("3", db.queryForObject("SELECT status FROM pln_issue", String.class));
    }

    @Test
    void payloadAndInboxModelMustAgree() {
        proof.setSourceSchemaVersion(2);
        assertThrows(com.kitex.common.core.exception.CheckedException.class, this::apply);
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM pln_completion_reopen_receipt", Integer.class));
    }

    @Test
    void repeatedSourcePlanIsRejectedBeforeAnyDecisionIsRecorded() {
        proof.setSourceLines(List.of(proof.getSourceLines().get(0), proof.getSourceLines().get(0)));
        assertThrows(com.kitex.common.core.exception.CheckedException.class, this::apply);
        assertEquals("3", db.queryForObject("SELECT status FROM pln_issue", String.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM pln_completion_reopen_receipt", Integer.class));
    }

    @Test
    void unapprovedMesEvidenceCannotReopenThePlan() {
        proof.setSourceValid(false);
        assertThrows(com.kitex.common.core.exception.CheckedException.class, this::apply);
        assertEquals("3", db.queryForObject("SELECT status FROM pln_issue", String.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"OTHER_FACTORY", "OTHER_TENANT", "TENANT_SKIP", "FACTORY_SKIP"})
    void theInboxMustRunInItsActualOrganization(String fault) {
        switch (fault) {
            case "OTHER_FACTORY" -> FactoryContextHolder.setFactoryCode("F2");
            case "OTHER_TENANT" -> TenantContextHolder.setTenantId(2L);
            case "TENANT_SKIP" -> TenantContextHolder.setTenantSkip();
            case "FACTORY_SKIP" -> FactoryContextHolder.setFactorySkip();
        }
        assertThrows(com.kitex.common.core.exception.CheckedException.class, this::apply);
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM pln_completion_reopen_receipt", Integer.class));
    }

    @Test
    void anUnissuedPlanCannotBeActivatedByAReopenNotification() {
        db.update("UPDATE pln_issue SET status='1'");
        assertThrows(com.kitex.common.core.exception.CheckedException.class, this::apply);
        assertEquals("1", db.queryForObject("SELECT status FROM pln_issue", String.class));
    }

    @Test
    void aReceiptFromAnotherFactoryCannotBeUsedAsAReplay() {
        db.update(
                "INSERT INTO"
                    + " pln_completion_reopen_receipt(id,tenant_id,factory_code,inbox_id,mes_outbox_id,issue_id,apply_result)"
                    + " VALUES(200,1,'F2',100,80,1,'APPLIED')");
        assertEquals(1, apply().appliedCount());
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT count(*) FROM pln_completion_reopen_receipt", Integer.class));
    }

    private <T> T dependency(Class<T> type) {
        return type.cast(dependencies.computeIfAbsent(type, k -> mock(type)));
    }

    private <T> T proxied(T target) {
        var proxy = new org.springframework.aop.framework.ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new org.springframework.transaction.interceptor.TransactionInterceptor(
                        new DataSourceTransactionManager(dependency(DataSource.class)),
                        new org.springframework.transaction.annotation
                                .AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    private com.kitex.mom.plan.service.impl.PlnAutomationEventProcessor pipeline()
            throws Exception {
        dependencies.put(PlnCompletionReopenEventHandler.class, handler);
        var type = com.kitex.mom.plan.service.impl.PlnAutomationEventWorker.class;
        var constructor = type.getDeclaredConstructors()[0];
        var worker =
                proxied(
                        (com.kitex.mom.plan.service.impl.PlnAutomationEventWorker)
                                constructor.newInstance(
                                        Arrays.stream(constructor.getParameterTypes())
                                                .map(this::dependency)
                                                .toArray()));
        var failed =
                proxied(
                        new com.kitex.mom.plan.service.impl.PlnAutomationEventFailureService(
                                dependency(PlnAutomationEventMapper.class),
                                dependency(PlnAutomationEventPolicyMapper.class),
                                dependency(PlnAutomationEventOperationMapper.class)));
        return new com.kitex.mom.plan.service.impl.PlnAutomationEventProcessor(
                dependency(PlnAutomationEventMapper.class), worker, failed);
    }

    private void inbox(Long id, Long tenant, String factory, MesPlanCompletionReopenEvidence source)
            throws Exception {
        String raw = payloadRaw(source);
        if (source.getSourceSchemaVersion() == null || source.getSourceSchemaVersion() == 1)
            raw = json.writeValueAsString(json.readTree(raw));
        db.update(
                "INSERT INTO"
                    + " pln_automation_event(id,tenant_id,factory_code,event_id,event_no,source_system,event_type,event_version,aggregate_type,aggregate_id,status,handler_code,attempt_count,next_attempt_time,priority,payload_json,payload_fingerprint,last_error_code,last_error_message)"
                    + " VALUES(?,?,?,?"
                    + " ,?,'MES','MES_COMPLETION_REOPENED',?,'MES_TASK',?,'RECEIVED','MES_COMPLETION_REOPEN_APPLY',0,TIMESTAMP"
                    + " '2026-01-01 00:00:00',10,?,?,'OLD_CODE','OLD_FAILURE')",
                id,
                tenant,
                factory,
                source.getEventId(),
                "EVT-" + id,
                source.getSourceSchemaVersion() == null ? 1 : source.getSourceSchemaVersion(),
                String.valueOf(source.getTaskId()),
                raw,
                digest(raw));
    }

    @Test
    void actualEventPipelineProcessesAllOrganizationsAndRestoresTheCallerContext()
            throws Exception {
        inbox(100L, 1L, "F1", proof);
        var second =
                new MesPlanCompletionReopenEvidence()
                        .setTenantId(2L)
                        .setFactoryCode("F2")
                        .setOutboxId(81L)
                        .setEventId("MES-COMPLETION-REOPEN:91:V1")
                        .setReverseRequestId(91L)
                        .setTaskId(12L)
                        .setTaskCode("TASK-2")
                        .setPlanNo("PLAN-2")
                        .setProductCode("MAT1")
                        .setCompleteQuantityBefore(new BigDecimal("2"))
                        .setCompleteQuantityAfter(BigDecimal.ONE)
                        .setCompletionVersionBefore(0)
                        .setCompletionVersionAfter(1)
                        .setReopenedTime(LocalDateTime.now())
                        .setPayloadFingerprint("e".repeat(64))
                        .setSourceValid(true)
                        .setSourceLines(
                                List.of(
                                        new MesPlanCompletionReopenEvidence.SourceLine()
                                                .setIssueId(2L)
                                                .setIssueNo("PLAN-2")
                                                .setAllocatedQuantity(new BigDecimal("2"))
                                                .setCompletedQuantity(BigDecimal.ONE)));
        sourceProofs.put(81L, second);
        inbox(101L, 2L, "F2", second);
        db.update(
                "INSERT INTO"
                    + " pln_issue(id,tenant_id,factory_code,issue_no,product_code,num,status,finish_time,finish_num)"
                    + " VALUES(2,2,'F2','PLAN-2','MAT1',5,'3',DATE '2026-10-24',1)");
        TenantContextHolder.setTenantId(99L);
        FactoryContextHolder.setFactoryCode("PREVIOUS");
        TenantContextHolder.setTenantSkip();
        FactoryContextHolder.setFactorySkip();
        assertEquals(2, pipeline().processDueEvents(100));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT count(*) FROM pln_issue WHERE status='2' AND finish_time IS NULL",
                        Integer.class));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT count(*) FROM pln_automation_event WHERE status='SUCCEEDED' AND"
                                + " next_attempt_time IS NULL AND last_error_code IS NULL AND"
                                + " last_error_message IS NULL",
                        Integer.class));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT count(*) FROM pln_completion_reopen_receipt", Integer.class));
        assertEquals(99L, TenantContextHolder.getTenantId());
        assertEquals("PREVIOUS", FactoryContextHolder.getFactoryCode());
        assertTrue(TenantContextHolder.getTenantSkip());
        assertTrue(FactoryContextHolder.getFactorySkip());
    }

    @Test
    void aBusinessProofFailureIsSentToManualHandlingInItsOwnOrganization() throws Exception {
        proof.setSourceValid(false);
        inbox(100L, 1L, "F1", proof);
        assertEquals(0, pipeline().processDueEvents(100));
        assertEquals(
                "WAITING_MANUAL",
                db.queryForObject("SELECT status FROM pln_automation_event", String.class));
        assertNull(
                db.queryForObject(
                        "SELECT next_attempt_time FROM pln_automation_event", LocalDateTime.class));
        assertEquals("3", db.queryForObject("SELECT status FROM pln_issue", String.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM pln_completion_reopen_receipt", Integer.class));
    }

    @Test
    void approvedEventFactsAreNotReducedToTheInteractivePersonsVisiblePlanSubset() {
        hidePlansFromInteractiveScope = true;
        var result = assertDoesNotThrow(this::apply);
        assertEquals(1, result.appliedCount());
        assertEquals("2", db.queryForObject("SELECT status FROM pln_issue", String.class));
    }

    @Test
    void aPermanentLegacyCompletionIsNotHiddenByInteractiveReceiptVisibility() {
        hideCallbacksFromInteractiveScope = true;
        db.update(
                "INSERT INTO"
                    + " pln_issue_callback_receipt(id,tenant_id,factory_code,request_id,issue_id,issue_no,request_fingerprint)"
                    + " VALUES(200,1,'F1','MES:TASK-1:COMPLETE_V1:PLAN-1',1,'PLAN-1',?)",
                "a".repeat(64));
        assertEquals(0, apply().appliedCount());
        assertEquals("3", db.queryForObject("SELECT status FROM pln_issue", String.class));
    }

    private ObjectMapper platformJson() {
        var builder = new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder();
        new com.kitex.common.core.config.JacksonConfiguration().customizer().customize(builder);
        return builder.build();
    }

    @Test
    void legacyHutoolPayloadCanBeReadByTheActualPlatformJsonConfiguration() throws Exception {
        proof.setPayloadFingerprint(null).setSourceValid(null);
        String raw = cn.hutool.json.JSONUtil.toJsonStr(proof);
        proof.setPayloadFingerprint("d".repeat(64)).setSourceValid(true);
        var payload = json.readTree(raw);
        event.setPayloadJson(raw).setPayloadFingerprint(digest(raw));
        var result = assertDoesNotThrow(() -> tx.execute(status -> handler.apply(event, payload)));
        assertEquals(1, result.appliedCount());
        assertEquals("2", db.queryForObject("SELECT status FROM pln_issue", String.class));
    }

    private com.kitex.mom.plan.service.impl.PlnAutomationEventServiceImpl receiver() {
        return proxied(
                new com.kitex.mom.plan.service.impl.PlnAutomationEventServiceImpl(
                        dependency(PlnAutomationEventMapper.class),
                        dependency(PlnAutomationEventPolicyMapper.class),
                        dependency(PlnAutomationEventOperationMapper.class),
                        json));
    }

    private com.kitex.mom.plan.dto.PlanAutomationEventDTO.ReceiveCommand incoming(
            String raw, int version) {
        var command = new com.kitex.mom.plan.dto.PlanAutomationEventDTO.ReceiveCommand();
        command.setEventId(proof.getEventId());
        command.setSourceSystem("MES");
        command.setEventType("MES_COMPLETION_REOPENED");
        command.setEventVersion(version);
        command.setAggregateType("MES_TASK");
        command.setAggregateId("11");
        command.setFactoryCode("F1");
        command.setPayloadJson(raw);
        return command;
    }

    @Test
    void newVersionKeepsExactSourceBytesAndDecimalQuantityThroughTheActualReceiver()
            throws Exception {
        proof.setSourceSchemaVersion(2)
                .setCompleteQuantityBefore(new BigDecimal("1234567890.12345678"))
                .setCompleteQuantityAfter(new BigDecimal("1234567889.12345678"))
                .setSourceLines(
                        List.of(
                                new MesPlanCompletionReopenEvidence.SourceLine()
                                        .setIssueNo("PLAN-1")
                                        .setAllocatedQuantity(new BigDecimal("1234567890.12345678"))
                                        .setCompletedQuantity(
                                                new BigDecimal("1234567889.12345678"))));
        String raw = payloadRaw(proof);
        proof.setPayloadFingerprint(digest(raw));
        db.update("UPDATE pln_issue SET num=1234567890.12345678");
        var first = receiver().receive(incoming(raw, 2));
        var second = receiver().receive(incoming(raw, 2));
        assertEquals(first.getInboxId(), second.getInboxId());
        assertEquals(
                raw,
                db.queryForObject("SELECT payload_json FROM pln_automation_event", String.class));
        assertEquals(1, pipeline().processDueEvents(100));
        assertEquals("2", db.queryForObject("SELECT status FROM pln_issue", String.class));
        assertEquals(
                0,
                new BigDecimal("1234567889.12345678")
                        .compareTo(
                                db.queryForObject(
                                        "SELECT completed_quantity FROM"
                                                + " pln_completion_reopen_receipt",
                                        BigDecimal.class)));
    }

    @Test
    void oldVersionRetainsItsOriginalNormalizedRequestIdentity() throws Exception {
        String raw = payloadRaw(proof);
        var first = receiver().receive(incoming(raw, 1));
        var again = receiver().receive(incoming("  " + raw + " ", 1));
        assertEquals(first.getInboxId(), again.getInboxId());
        assertEquals(
                1, db.queryForObject("SELECT count(*) FROM pln_automation_event", Integer.class));
    }

    @Test
    void
            legacyNormalizedDecimalsUseVerifiedOriginalQuantitiesEvenWhenTheTransportRoundsAwayTheDecrease() {
        proof.setCompleteQuantityBefore(new BigDecimal("1000000000.00000002"))
                .setCompleteQuantityAfter(new BigDecimal("1000000000.00000001"))
                .setSourceLines(
                        List.of(
                                new MesPlanCompletionReopenEvidence.SourceLine()
                                        .setIssueId(1L)
                                        .setIssueNo("PLAN-1")
                                        .setAllocatedQuantity(new BigDecimal("2000000000"))
                                        .setCompletedQuantity(
                                                new BigDecimal("1000000000.00000001"))));
        db.update("UPDATE pln_issue SET num=2000000000");
        assertDoesNotThrow(this::apply);
        assertEquals(
                0,
                new BigDecimal("1000000000.00000001")
                        .compareTo(
                                db.queryForObject(
                                        "SELECT completed_quantity FROM"
                                            + " pln_completion_reopen_receipt",
                                        BigDecimal.class)));
    }

    private void table(MybatisConfiguration config, Class<?> type) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(config, "reopen-ddl"), type);
        var info = TableInfoHelper.getTableInfo(type);
        var ddl =
                new StringBuilder("CREATE TABLE ")
                        .append(info.getTableName())
                        .append("(id bigint primary key");
        for (var f : info.getFieldList()) {
            Class<?> t = f.getPropertyType();
            String sql =
                    t == Long.class
                            ? "bigint"
                            : t == Integer.class
                                    ? "integer"
                                    : t == Boolean.class
                                            ? "boolean"
                                            : t == BigDecimal.class
                                                    ? "decimal(24,8)"
                                                    : t == LocalDate.class
                                                            ? "date"
                                                            : t == LocalDateTime.class
                                                                            || t
                                                                                    == java.util
                                                                                            .Date
                                                                                            .class
                                                                    ? "timestamp"
                                                                    : "varchar(64000)";
            ddl.append(',').append(f.getColumn()).append(' ').append(sql);
            if (f.getColumn().equals("del_flag")) ddl.append(" default '0'");
        }
        db.execute(ddl.append(')').toString());
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }
}
