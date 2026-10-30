package com.kitex.mom.plan.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.oms.dto.OmsOrderLifeCycleDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.impl.*;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** 公开旧回传入口及本地事务，PG请求锁用H2行锁适配。 */
class PlnMesCompletionTest {
    private JdbcTemplate db;
    private PlnIssueService service;
    private PlnMesCompletionService completion;
    private com.kitex.mom.mes.dto.MesPlanCompletionDTO.Evidence evidence;
    private com.kitex.mom.mes.dto.MesPlanCompletionDTO.Progress progress;
    private com.kitex.mom.mes.api.feign.RemoteMesPlanCompletionService remote;
    private com.kitex.mom.plan.dto.PlanMesCompletionDTO.Command command;
    private PlnMesCompletionReceiptMapper receiptRows;
    private final Map<Class<?>, Object> dependencies = new HashMap<>();

    @BeforeEach
    void setup() throws Exception {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:plan-completion-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        source.setDriverClassName("org.h2.Driver");
        dependencies.put(javax.sql.DataSource.class,source);
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE request_lock(id int primary key)");
        db.update("INSERT INTO request_lock VALUES(1)");
        var config = new MybatisConfiguration();
        var global = new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(
                new com.kitex.common.data.mybatis.MybatisPlusMetaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(config, global);
        config.addMapper(PlnIssueMapper.class);
        config.addMapper(PlnIssueCallbackReceiptMapper.class);
        config.addMapper(PlnMesCompletionReceiptMapper.class);
        config.addMapper(PlnCompletionReopenReceiptMapper.class);
        table(config, PlnIssueEntity.class);
        table(config, PlnIssueCallbackReceiptEntity.class);
        table(config, PlnMesCompletionReceiptEntity.class);
        table(config, PlnCompletionReopenReceiptEntity.class);
        db.execute("CREATE SCHEMA mom_plan");
        for (String name :
                List.of(
                        "pln_issue",
                        "pln_mes_completion_receipt",
                        "pln_completion_reopen_receipt",
                        "pln_issue_callback_receipt"))
            db.execute("CREATE SYNONYM mom_plan." + name + " FOR PUBLIC." + name);
        db.execute(
                "CREATE UNIQUE INDEX completion_owner ON"
                        + " pln_mes_completion_receipt(tenant_id,factory_code,outbox_id,issue_id)");
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(config);
        factory.setGlobalConfig(global);
        var plugins = new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
        plugins.addInnerInterceptor(
                new com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor(
                        new com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler() {
                            @Override
                            public net.sf.jsqlparser.expression.Expression getTenantId() {
                                return new net.sf.jsqlparser.expression.LongValue(
                                        TenantContextHolder.getTenantId());
                            }

                            @Override
                            public boolean ignoreTable(String table) {
                                return TenantContextHolder.getTenantSkip();
                            }
                        }));
        factory.setPlugins(plugins);
        var sessions = new SqlSessionTemplate(factory.getObject());
        var actual = sessions.getMapper(PlnIssueCallbackReceiptMapper.class);
        var callbacks =
                (PlnIssueCallbackReceiptMapper)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {PlnIssueCallbackReceiptMapper.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("lockRequest"))
                                        return db.queryForObject(
                                                "SELECT id FROM request_lock WHERE id=1 FOR UPDATE",
                                                Integer.class);
                                    try {
                                        return method.invoke(actual, args);
                                    } catch (java.lang.reflect.InvocationTargetException e) {
                                        throw e.getCause();
                                    }
                                });
        dependencies.put(PlnIssueCallbackReceiptMapper.class, callbacks);
        dependencies.put(PlnIssueMapper.class, sessions.getMapper(PlnIssueMapper.class));
        dependencies.put(
                PlnCompletionReopenReceiptMapper.class,
                sessions.getMapper(PlnCompletionReopenReceiptMapper.class));
        var actualReceipts = sessions.getMapper(PlnMesCompletionReceiptMapper.class);
        receiptRows =
                (PlnMesCompletionReceiptMapper)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {PlnMesCompletionReceiptMapper.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("lockRequest"))
                                        return db.queryForObject(
                                                "SELECT id FROM request_lock WHERE id=1 FOR UPDATE",
                                                Integer.class);
                                    try {
                                        return method.invoke(actualReceipts, args);
                                    } catch (java.lang.reflect.InvocationTargetException e) {
                                        throw e.getCause();
                                    }
                                });
        dependencies.put(PlnMesCompletionReceiptMapper.class, receiptRows);
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        dependencies.put(com.fasterxml.jackson.databind.ObjectMapper.class, json);
        remote = mock(com.kitex.mom.mes.api.feign.RemoteMesPlanCompletionService.class);
        when(remote.evidence(anyLong(), anyString())).thenAnswer(call -> R.ok(evidence));
        when(remote.progress(anyLong(), anyLong(), anyString(), anyString()))
                .thenAnswer(call -> R.ok(progress));
        var targetCompletion = new PlnMesCompletionService(receiptRows, remote, json, source);
        var completionProxy = new ProxyFactory(targetCompletion);
        completionProxy.setProxyTargetClass(true);
        completionProxy.addAdvice(
                new TransactionInterceptor(
                        new DataSourceTransactionManager(source),
                        new AnnotationTransactionAttributeSource()));
        completion = (PlnMesCompletionService) completionProxy.getProxy();
        command =
                new com.kitex.mom.plan.dto.PlanMesCompletionDTO.Command()
                        .setOutboxId(10L)
                        .setEventId("MES-COMPLETE:11:V0")
                        .setPayloadFingerprint("a".repeat(64))
                        .setFactoryCode("F1");
        var event =
                new com.kitex.mom.mes.dto.MesPlanCompletionDTO.Event()
                        .setSchemaVersion(1)
                        .setOutboxId(10L)
                        .setEventId(command.getEventId())
                        .setTenantId(1L)
                        .setFactoryCode("F1")
                        .setTaskId(11L)
                        .setTaskCode("TASK-1")
                        .setProductCode("MAT1")
                        .setCompletionVersion(0)
                        .setCompletedQuantity(new BigDecimal("2"))
                        .setCompletedAt(LocalDateTime.of(2026, 10, 24, 8, 0))
                        .setSources(
                                List.of(
                                        new com.kitex.mom.mes.dto.MesPlanCompletionDTO.Line()
                                                .setIssueNo("PLAN-1")
                                                .setAllocatedQuantity(new BigDecimal("2"))
                                                .setCompletedQuantity(new BigDecimal("2"))));
        evidence =
                new com.kitex.mom.mes.dto.MesPlanCompletionDTO.Evidence()
                        .setEvent(event)
                        .setPayloadFingerprint(command.getPayloadFingerprint())
                        .setSourceValid(true);
        progress =
                new com.kitex.mom.mes.dto.MesPlanCompletionDTO.Progress()
                        .setTenantId(1L)
                        .setFactoryCode("F1")
                        .setOutboxId(10L)
                        .setPayloadFingerprint(command.getPayloadFingerprint())
                        .setIssueId(1L)
                        .setIssueNo("PLAN-1")
                        .setProductCode("MAT1")
                        .setAllocatedQuantity(new BigDecimal("2"))
                        .setCompletedQuantity(new BigDecimal("2"))
                        .setAllFinished(true)
                        .setTaskCount(1)
                        .setCompletedAt(LocalDateTime.of(2026, 10, 25, 10, 0))
                        .setSourceFingerprint("c".repeat(64));
        var target = construct(PlnIssueServiceImpl.class);
        ReflectionTestUtils.setField(
                target, "baseMapper", sessions.getMapper(PlnIssueMapper.class));
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(
                        new DataSourceTransactionManager(source),
                        new AnnotationTransactionAttributeSource()));
        service = (PlnIssueService) proxy.getProxy();
        db.update(
                "INSERT INTO"
                    + " pln_issue(id,tenant_id,factory_code,issue_no,product_code,num,status,type)"
                    + " VALUES(1,1,'F1','PLAN-1','MAT1',5,'2','issue')");
    }

    private OmsOrderLifeCycleDTO callback(String status) {
        var dto = new OmsOrderLifeCycleDTO();
        dto.setRequestId("MES:BATCH-1:COMPLETE_V0:1");
        dto.setNo("PLAN-1");
        dto.setProductCode("MAT1");
        dto.setType("mes");
        dto.setIssueStatus(status);
        dto.setStatus("2");
        return dto;
    }

    @Test
    void firstSplitTaskCannotCompleteThePlanThroughAnUnverifiedLegacyCallback() {
        assertThrows(CheckedException.class, () -> service.edit(List.of(callback("3"))));
        assertEquals("2", db.queryForObject("SELECT status FROM pln_issue", String.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM pln_issue_callback_receipt", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "4"})
    void delayedRunningCallbackCannotRegressACompletedOrClosedPlan(String state) {
        db.update("UPDATE pln_issue SET status=?", state);
        assertTrue(service.edit(List.of(callback("2"))));
        assertEquals(state, db.queryForObject("SELECT status FROM pln_issue", String.class));
    }

    private String state() {
        return db.queryForObject("SELECT status FROM pln_issue WHERE id=1", String.class);
    }

    private void fullProgress() {
        progress.setAllocatedQuantity(new BigDecimal("5"))
                .setCompletedQuantity(new BigDecimal("5"))
                .setTaskCount(2);
    }

    @Test
    void partialAcceptedQuantityDoesNotFinishAnOtherwiseFinishedBatch() {
        var receipt = completion.reconcile(command);
        assertNotNull(receipt);
        assertEquals("2", state());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM pln_mes_completion_receipt", Integer.class));
    }

    @Test
    void completeAllocationAndAllFinishedTasksCompleteThePlanWithoutChangingWarehouseQuantity() {
        fullProgress();
        db.update("UPDATE pln_issue SET finish_num=1");
        assertNotNull(completion.reconcile(command));
        assertEquals("3", state());
        assertEquals(
                0,
                BigDecimal.ONE.compareTo(
                        db.queryForObject(
                                "SELECT finish_num FROM pln_issue WHERE id=1", BigDecimal.class)));
        assertEquals(
                LocalDate.of(2026, 10, 25),
                db.queryForObject("SELECT finish_time FROM pln_issue WHERE id=1", LocalDate.class));
    }

    @Test
    void fullAcceptedQuantityWithAnActivePeerStillRemainsInProduction() {
        fullProgress();
        progress.setAllFinished(false).setCompletedQuantity(new BigDecimal("2"));
        assertNotNull(completion.reconcile(command));
        assertEquals("2", state());
    }

    @Test
    void shortActualOutputPreservesTheExistingFullAllocationFinishRule() {
        fullProgress();
        progress.setCompletedQuantity(new BigDecimal("3"));
        assertNotNull(completion.reconcile(command));
        assertEquals("3", state());
    }

    @Test
    void closedPlanIsAcknowledgedWithoutRestoringItsStatusOrInventingProgress() {
        db.update("UPDATE pln_issue SET status='4'");
        when(remote.progress(anyLong(), anyLong(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("must not query closed plan progress"));
        assertNotNull(completion.reconcile(command));
        assertEquals("4", state());
        assertEquals(
                "CLOSED_IGNORED",
                db.queryForObject("SELECT decision FROM pln_mes_completion_receipt", String.class));
        assertNull(
                db.queryForObject(
                        "SELECT allocated_quantity FROM pln_mes_completion_receipt",
                        BigDecimal.class));
    }

    @Test
    void aLostAcknowledgementReturnsTheSameReceiptEvenWhenMesIsLaterUnavailable() {
        var first = completion.reconcile(command);
        assertNotNull(first);
        when(remote.evidence(anyLong(), anyString()))
                .thenThrow(new IllegalStateException("MES unavailable"));
        var second = completion.reconcile(command);
        assertNotNull(second);
        assertEquals(first.getReceiptIds(), second.getReceiptIds());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM pln_mes_completion_receipt", Integer.class));
    }

    @Test
    void anEarlierEventReplayDoesNotUndoTheLastBatchCompletion() {
        var first = completion.reconcile(command);
        assertNotNull(first);
        var earlier =
                new com.kitex.mom.plan.dto.PlanMesCompletionDTO.Command()
                        .setOutboxId(10L)
                        .setEventId(command.getEventId())
                        .setPayloadFingerprint(command.getPayloadFingerprint())
                        .setFactoryCode("F1");
        command.setOutboxId(20L)
                .setEventId("MES-COMPLETE:12:V0")
                .setPayloadFingerprint("b".repeat(64));
        evidence.getEvent()
                .setOutboxId(20L)
                .setEventId(command.getEventId())
                .setTaskId(12L)
                .setTaskCode("TASK-2");
        evidence.setPayloadFingerprint(command.getPayloadFingerprint());
        progress.setOutboxId(20L).setPayloadFingerprint(command.getPayloadFingerprint());
        fullProgress();
        assertNotNull(completion.reconcile(command));
        assertEquals("3", state());
        assertNotNull(completion.reconcile(earlier));
        assertEquals("3", state());
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT count(*) FROM pln_mes_completion_receipt", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SOURCE_INVALID",
                "WRONG_FACTORY",
                "HASH_MISMATCH",
                "MISSING_PROGRESS",
                "OVER_ALLOCATED"
            })
    void unavailableOrMismatchedSourceCannotProduceAReceipt(String fault) {
        switch (fault) {
            case "SOURCE_INVALID" -> evidence.setSourceValid(false);
            case "WRONG_FACTORY" -> evidence.getEvent().setFactoryCode("F2");
            case "HASH_MISMATCH" -> evidence.setPayloadFingerprint("b".repeat(64));
            case "MISSING_PROGRESS" -> progress = null;
            case "OVER_ALLOCATED" -> progress.setAllocatedQuantity(new BigDecimal("6"));
        }
        assertThrows(CheckedException.class, () -> completion.reconcile(command));
        assertEquals("2", state());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM pln_mes_completion_receipt", Integer.class));
    }

    @Test
    void receiptFailureRollsBackThePlanDecision() {
        fullProgress();
        db.execute(
                "ALTER TABLE pln_mes_completion_receipt ADD CONSTRAINT reject_receipt"
                        + " CHECK(decision<>'COMPLETED')");
        assertThrows(RuntimeException.class, () -> completion.reconcile(command));
        assertEquals("2", state());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM pln_mes_completion_receipt", Integer.class));
    }

    @Test
    void changedPayloadCannotReuseAnExistingEventReceipt() {
        assertNotNull(completion.reconcile(command));
        command.setPayloadFingerprint("b".repeat(64));
        assertThrows(CheckedException.class, () -> completion.reconcile(command));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM pln_mes_completion_receipt", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void theNewCompletionReceiptSupersedesItsOlderReopenEvent(int generation) {
        evidence.getEvent()
                .setCompletionVersion(generation)
                .setEventId("MES-COMPLETE:11:V" + generation);
        command.setEventId(evidence.getEvent().getEventId());
        fullProgress();
        assertNotNull(completion.reconcile(command));
        assertEquals("3", state());
        var proof =
                new com.kitex.mom.mes.dto.MesPlanCompletionReopenEvidence()
                        .setTenantId(1L)
                        .setFactoryCode("F1")
                        .setOutboxId(80L)
                        .setEventId("REOPEN-80")
                        .setReverseRequestId(90L)
                        .setTaskId(11L)
                        .setTaskCode("TASK-1")
                        .setPlanNo("PLAN-1")
                        .setProductCode("MAT1")
                        .setCompletionVersionBefore(0)
                        .setCompletionVersionAfter(1)
                        .setCompleteQuantityBefore(new BigDecimal("3"))
                        .setCompleteQuantityAfter(new BigDecimal("2"))
                        .setPayloadFingerprint("d".repeat(64))
                        .setSourceValid(true)
                        .setSourceLines(
                                List.of(
                                        new com.kitex.mom.mes.dto.MesPlanCompletionReopenEvidence
                                                        .SourceLine()
                                                .setIssueId(1L)
                                                .setIssueNo("PLAN-1")
                                                .setAllocatedQuantity(new BigDecimal("2"))
                                                .setCompletedQuantity(BigDecimal.ONE)));
        when(dependency(com.kitex.mom.mes.api.feign.RemoteMesPlanCompletionReopenService.class)
                        .evidence(80L, "F1"))
                .thenReturn(R.ok(proof));
        var event =
                new PlnAutomationEventEntity()
                        .setEventId("REOPEN-80")
                        .setFactoryCode("F1")
                        .setSourceSystem("MES")
                        .setEventType("MES_COMPLETION_REOPENED")
                        .setAggregateType("MES_TASK")
                        .setAggregateId("11");
        event.setId(100L);
        event.setTenantId(1L);
        try {
            var handler = construct(PlnCompletionReopenEventHandler.class);
            event.setPayloadJson(cn.hutool.json.JSONUtil.toJsonStr(proof));
            var result =
                    new org.springframework.transaction.support.TransactionTemplate(
                                    new DataSourceTransactionManager(
                                            dependency(javax.sql.DataSource.class)))
                            .execute(
                                    status ->
                                            handler.apply(
                                                    event,
                                                    dependency(
                                                                    com.fasterxml.jackson.databind
                                                                            .ObjectMapper.class)
                                                            .valueToTree(proof)));
            assertEquals(0, result.appliedCount());
            assertEquals("3", state());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void sourcesAreReconciledAtomicallyAcrossMultiplePlans() {
        db.update(
                "INSERT INTO"
                    + " pln_issue(id,tenant_id,factory_code,issue_no,product_code,num,status,type)"
                    + " VALUES(2,1,'F1','PLAN-2','MAT1',3,'2','issue')");
        var event = evidence.getEvent();
        event.setApsBatchNo("BATCH-1").setCompletedQuantity(new BigDecimal("5"));
        event.setSources(
                List.of(
                        new com.kitex.mom.mes.dto.MesPlanCompletionDTO.Line()
                                .setIssueId(2L)
                                .setIssueNo("PLAN-2")
                                .setAllocatedQuantity(new BigDecimal("3"))
                                .setCompletedQuantity(new BigDecimal("3")),
                        new com.kitex.mom.mes.dto.MesPlanCompletionDTO.Line()
                                .setIssueId(1L)
                                .setIssueNo("PLAN-1")
                                .setAllocatedQuantity(new BigDecimal("2"))
                                .setCompletedQuantity(new BigDecimal("2"))));
        when(remote.progress(anyLong(), anyLong(), anyString(), anyString()))
                .thenAnswer(
                        call -> {
                            if (Long.valueOf(2L).equals(call.getArgument(1)))
                                throw new IllegalStateException("second source unavailable");
                            return R.ok(progress);
                        });
        assertThrows(IllegalStateException.class, () -> completion.reconcile(command));
        assertEquals("2", state());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM pln_mes_completion_receipt", Integer.class));
        when(remote.progress(anyLong(), eq(2L), anyString(), anyString()))
                .thenAnswer(
                        call ->
                                R.ok(
                                        new com.kitex.mom.mes.dto.MesPlanCompletionDTO.Progress()
                                                .setTenantId(1L)
                                                .setFactoryCode("F1")
                                                .setOutboxId(10L)
                                                .setPayloadFingerprint(
                                                        command.getPayloadFingerprint())
                                                .setIssueId(2L)
                                                .setIssueNo("PLAN-2")
                                                .setProductCode("MAT1")
                                                .setAllocatedQuantity(new BigDecimal("3"))
                                                .setCompletedQuantity(new BigDecimal("3"))
                                                .setAllFinished(true)
                                                .setTaskCount(1)
                                                .setCompletedAt(LocalDateTime.now())
                                                .setSourceFingerprint("d".repeat(64))));
        var receipt = completion.reconcile(command);
        assertEquals(2, receipt.getReceiptIds().size());
        assertEquals("2", state());
        assertEquals(
                "3", db.queryForObject("SELECT status FROM pln_issue WHERE id=2", String.class));
        db.update("DELETE FROM pln_mes_completion_receipt WHERE issue_id=2");
        assertThrows(CheckedException.class, () -> completion.reconcile(command));
    }

    @Test
    void legacyOriginalCompletionReceiptsRemainReplayableWithoutASecondStatusWrite() {
        var dto = callback("3");
        String fp = (String) ReflectionTestUtils.invokeMethod(service, "callbackFingerprint", dto);
        db.update(
                "INSERT INTO"
                    + " pln_issue_callback_receipt(id,tenant_id,factory_code,request_id,request_fingerprint,issue_id)"
                    + " VALUES(40,1,'F1',?,?,1)",
                dto.getRequestId(),
                fp);
        assertTrue(service.edit(List.of(dto)));
        assertEquals("2", state());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM pln_issue_callback_receipt", Integer.class));
    }

    @Test
    void sameLegacyRequestFromAnotherFactoryIsRejected() {
        var dto = callback("2");
        String fp = (String) ReflectionTestUtils.invokeMethod(service, "callbackFingerprint", dto);
        db.update(
                "INSERT INTO"
                    + " pln_issue_callback_receipt(id,tenant_id,factory_code,request_id,request_fingerprint,issue_id)"
                    + " VALUES(40,1,'F2',?,?,1)",
                dto.getRequestId(),
                fp);
        assertThrows(CheckedException.class, () -> service.edit(List.of(dto)));
        assertEquals("2", state());
    }

    private void table(MybatisConfiguration config, Class<?> type) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(config, "completion-test"), type);
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

    private <T> T dependency(Class<T> type) {
        return type.cast(dependencies.computeIfAbsent(type, k -> mock(type)));
    }

    private <T> T construct(Class<T> type) throws Exception {
        var ctor = type.getDeclaredConstructors()[0];
        return type.cast(
                ctor.newInstance(
                        Arrays.stream(ctor.getParameterTypes()).map(this::dependency).toArray()));
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }
}
