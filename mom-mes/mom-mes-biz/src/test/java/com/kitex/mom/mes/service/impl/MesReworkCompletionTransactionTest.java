package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.*;
import com.kitex.mom.qms.api.feign.RemoteQmsReworkService;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real MES parent/operation SQL and callback-state transaction; QMS is an external adapted port.
 */
class MesReworkCompletionTransactionTest {
    JdbcTemplate db;
    MesTaskService service;
    RemoteQmsReworkService remote;
    MesActualFactoryAccessService access;
    org.springframework.transaction.PlatformTransactionManager transactions;
    final AtomicInteger sends = new AtomicInteger();
    final Fault fault = new Fault();
    SqlSessionTemplate sessions;

    @BeforeEach
    void setup() throws Exception {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:rework_completion_"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        db = new JdbcTemplate(ds);
        db.execute("CREATE SCHEMA mom_mes");
        db.execute(
                "CREATE TABLE mom_mes.mes_rework_completion_outbox(id BIGINT PRIMARY KEY,tenant_id"
                    + " BIGINT NOT NULL,factory_code VARCHAR(200) NOT NULL,task_id BIGINT NOT"
                    + " NULL,request_id VARCHAR(200) NOT NULL,payload CLOB NOT"
                    + " NULL,payload_fingerprint VARCHAR(64) NOT NULL,created_by BIGINT,created_at"
                    + " TIMESTAMP NOT"
                    + " NULL,UNIQUE(tenant_id,factory_code,task_id),UNIQUE(tenant_id,request_id))");
        db.execute(
                "CREATE ALIAS mom_mes.mom_rework_completion_outbox_ready_v1 FOR"
                    + " 'com.kitex.mom.mes.service.impl.MesReworkCompletionOutboxTransactionTest.installedVersion'");
        var config = new MybatisConfiguration();
        var global = new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(
                new com.kitex.common.data.mybatis.MybatisPlusMetaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(config, global);
        for (var type : List.of(MesTaskEntity.class, MesProduceTaskEntity.class))
            table(config, type);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(config, "completion-original"),
                MesReworkCompletionOutboxEntity.class);
        config.addMapper(MesReworkCompletionOutboxMapper.class);
        config.addMapper(MesTaskMapper.class);
        config.addMapper(MesProduceTaskMapper.class);
        config.addInterceptor(fault);
        var sf = new MybatisSqlSessionFactoryBean();
        sf.setDataSource(ds);
        sf.setConfiguration(config);
        sf.setGlobalConfig(global);
        var session = new SqlSessionTemplate(sf.getObject());
        sessions = session;
        var produce = mock(MesProduceTaskService.class);
        when(produce.getOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenAnswer(
                        c ->
                                session.getMapper(MesProduceTaskMapper.class)
                                        .selectOne(c.getArgument(0)));
        remote = mock(RemoteQmsReworkService.class);
        when(remote.mesComplete(any()))
                .thenAnswer(
                        c -> {
                            sends.incrementAndGet();
                            return R.ok(901L);
                        });
        access = mock(MesActualFactoryAccessService.class);
        when(access.require("write")).thenReturn("F1");
        transactions = new DataSourceTransactionManager(ds);
        var coordinator = newCoordinator();
        var constructor = MesTaskServiceImpl.class.getDeclaredConstructors()[0];
        Object[] dependencies =
                Arrays.stream(constructor.getParameterTypes())
                        .map(
                                t -> {
                                    if (t == MesProduceTaskService.class) return produce;
                                    if (t == MesReworkCompletionCoordinator.class)
                                        return coordinator;
                                    if (t == MesActualFactoryAccessService.class) return access;
                                    return mock(t);
                                })
                        .toArray();
        var target = constructor.newInstance(dependencies);
        ReflectionTestUtils.setField(target, "baseMapper", session.getMapper(MesTaskMapper.class));
        var proxy = new ProxyFactory(target);
        proxy.setInterfaces(MesTaskService.class);
        proxy.addAdvice(
                new TransactionInterceptor(
                        transactions, new AnnotationTransactionAttributeSource()));
        service = (MesTaskService) proxy.getProxy();
        db.update(
                "INSERT INTO"
                    + " mes_task(id,tenant_id,factory_code,code,resource_type,source_task_code,rework_request_id,status,notice_num,complete_num)"
                    + " VALUES(1,1,'F1','RW1','REWORK','MT0','QMS-RW-PROCEDURE-80','FINISH',2,2)");
        db.update(
                "UPDATE mes_task SET"
                    + " mat_code='MAT',source_inspection_type='PROCEDURE',source_inspection_task_id=700,source_handle_record_id=80,source_produce_task_code='P0',rework_process_code='PROC',rework_reason='接线返修'"
                    + " WHERE id=1");
        db.update(
                "INSERT INTO mes_task(id,tenant_id,factory_code,code,status)"
                        + " VALUES(2,1,'F1','MT0','FINISH')");
        db.update(
                "INSERT INTO"
                    + " mes_produce_task(id,tenant_id,factory_code,code,task_code,status,last_route,route_code,route_name,notice_num,complete_num)"
                    + " VALUES(3,1,'F1','RP1','RW1','FINISH',TRUE,'PROC','接线',2,2)");
    }

    MesReworkCompletionCoordinator newCoordinator() {
        var outbox =
                new MesReworkCompletionOutbox(
                        sessions.getMapper(MesReworkCompletionOutboxMapper.class),
                        sessions.getMapper(MesTaskMapper.class),
                        sessions.getMapper(MesProduceTaskMapper.class),
                        mock(MesApsSourceQuantityService.class),
                        remote);
        var proxy = new ProxyFactory(outbox);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(
                        transactions, new AnnotationTransactionAttributeSource()));
        return new MesReworkCompletionCoordinator((MesReworkCompletionOutbox) proxy.getProxy());
    }

    @AfterEach
    void close() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void validCompletionSavesAndReplaysTheSamePositiveReinspection() {
        assertEquals(901L, service.notifyReworkCompletion(1L, "RP1", new BigDecimal("2")));
        assertEquals(901L, service.notifyReworkCompletion(1L, "RP1", new BigDecimal("2.00")));
        assertEquals(1, sends.get());
        assertEquals("SUCCEEDED", status());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT rework_callback_count FROM mes_task WHERE id=1", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "operationOwner",
                "operationFactory",
                "taskFactory",
                "unfinishedOperation",
                "unfinishedOrder",
                "wrongQuantity",
                "zeroQuantity"
            })
    void invalidLocalCompletionProofNeverSendsToQms(String invalid) {
        BigDecimal quantity = new BigDecimal("2");
        switch (invalid) {
            case "operationOwner" ->
                    db.update("UPDATE mes_produce_task SET task_code='OTHER' WHERE id=3");
            case "operationFactory" ->
                    db.update("UPDATE mes_produce_task SET factory_code='F2' WHERE id=3");
            case "taskFactory" -> db.update("UPDATE mes_task SET factory_code='F2' WHERE id=1");
            case "unfinishedOperation" ->
                    db.update("UPDATE mes_produce_task SET status='RUNNING' WHERE id=3");
            case "unfinishedOrder" -> db.update("UPDATE mes_task SET status='RUNNING' WHERE id=1");
            case "wrongQuantity" -> quantity = BigDecimal.ONE;
            case "zeroQuantity" -> quantity = BigDecimal.ZERO;
        }
        BigDecimal requested = quantity;
        assertThrows(
                RuntimeException.class, () -> service.notifyReworkCompletion(1L, "RP1", requested));
        assertEquals(0, sends.get());
        assertNull(status());
    }

    @Test
    void anExistingSuccessDoesNotSkipTheCompletionIdentityAndQuantityChecks() {
        service.notifyReworkCompletion(1L, "RP1", new BigDecimal("2"));
        assertThrows(
                RuntimeException.class,
                () -> service.notifyReworkCompletion(1L, "OTHER", BigDecimal.ONE));
        assertEquals(1, sends.get());
    }

    @Test
    void aNonpositiveReinspectionReceiptIsNotSavedAsSuccess() {
        when(remote.mesComplete(any())).thenReturn(R.ok(0L));
        assertNull(service.notifyReworkCompletion(1L, "RP1", new BigDecimal("2")));
        assertEquals("FAILED", status());
        assertNull(
                db.queryForObject(
                        "SELECT reinspection_task_id FROM mes_task WHERE id=1", Long.class));
    }

    @Test
    void failedStatePersistenceCannotBeSwallowed() {
        when(remote.mesComplete(any())).thenThrow(new IllegalStateException("network unknown"));
        fault.lose = true;
        assertThrows(
                RuntimeException.class,
                () -> service.notifyReworkCompletion(1L, "RP1", new BigDecimal("2")));
        assertNull(status());
    }

    @Test
    void manualRetryRequiresActualFactoryWrite() {
        when(access.require("write")).thenThrow(new IllegalStateException("factory denied"));
        assertThrows(RuntimeException.class, () -> service.retryReworkCallback(1L));
        assertEquals(0, sends.get());
    }

    @Test
    void returnedReworkCodeIsResponseData() {
        var tasks = mock(MesTaskService.class);
        when(tasks.addRework(any())).thenReturn("RW-20261031");
        var controller =
                new com.kitex.mom.mes.controller.MesTaskController(
                        tasks,
                        mock(MesTaskInWmsCoordinator.class),
                        mock(com.kitex.mom.mes.batch.BatchGeneratorService.class));
        assertEquals(
                "RW-20261031",
                controller.addRework(new com.kitex.mom.mes.dto.MesTaskReceiveDTO()).getData());
    }

    @ParameterizedTest
    @ValueSource(strings = {"quantity", "sourceOrder", "material", "process", "reason", "factory"})
    void changedDispatchCannotReuseAnExistingMesReworkOrder(String changed) {
        var c = dispatch();
        switch (changed) {
            case "quantity" -> c.setNoticeNum(BigDecimal.ONE);
            case "sourceOrder" -> c.setSourceTaskCode("OTHER");
            case "material" -> c.setMatCode("OTHER");
            case "process" -> c.setReworkProcessCode("OTHER");
            case "reason" -> c.setReworkReason("OTHER");
            case "factory" -> db.update("UPDATE mes_task SET factory_code='F2' WHERE id=1");
        }
        assertThrows(RuntimeException.class, () -> service.addRework(c));
    }

    @Test
    void aMatchingDispatchReplaysItsOriginalMesWorkorder() {
        assertEquals("RW1", service.addRework(dispatch()));
    }

    private com.kitex.mom.mes.dto.MesTaskReceiveDTO dispatch() {
        var c = new com.kitex.mom.mes.dto.MesTaskReceiveDTO();
        c.setReworkRequestId("QMS-RW-PROCEDURE-80");
        c.setSourceTaskCode("MT0");
        c.setSourceProduceTaskCode("P0");
        c.setSourceInspectionType("PROCEDURE");
        c.setSourceInspectionTaskId(700L);
        c.setSourceHandleRecordId(80L);
        c.setNoticeNum(new BigDecimal("2"));
        c.setMatCode("MAT");
        c.setReworkProcessCode("PROC");
        c.setReworkReason("接线返修");
        return c;
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 900L})
    void aDifferentReceiptCannotReplaceAnExistingChildBinding(long previous) {
        db.update(
                "UPDATE mes_task SET rework_callback_status='FAILED',reinspection_task_id=? WHERE"
                        + " id=1",
                previous);
        assertThrows(
                RuntimeException.class,
                () -> service.notifyReworkCompletion(1L, "RP1", new BigDecimal("2")));
        assertEquals(
                previous,
                db.queryForObject(
                        "SELECT reinspection_task_id FROM mes_task WHERE id=1", Long.class));
        assertEquals("FAILED", status());
    }

    @Test
    void aCommittedConcurrentDispatchIsReadAgainAfterTheSourceParentLock() throws Exception {
        db.update("UPDATE mes_task SET rework_request_id='OTHER' WHERE id=1");
        fault.trackDispatch = true;
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        var future =
                new java.util.concurrent.atomic.AtomicReference<
                        java.util.concurrent.Future<String>>();
        try {
            new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .executeWithoutResult(
                            tx -> {
                                db.queryForObject(
                                        "SELECT id FROM mes_task WHERE id=2 FOR UPDATE",
                                        Long.class);
                                db.update(
                                        "UPDATE mes_task SET"
                                                + " rework_request_id='QMS-RW-PROCEDURE-80' WHERE"
                                                + " id=1");
                                future.set(
                                        worker.submit(
                                                () -> {
                                                    TenantContextHolder.setTenantId(1L);
                                                    FactoryContextHolder.setFactoryCode("F1");
                                                    try {
                                                        return service.addRework(dispatch());
                                                    } finally {
                                                        TenantContextHolder.clear();
                                                        FactoryContextHolder.clear();
                                                    }
                                                }));
                                try {
                                    assertTrue(
                                            fault.sourceAttempt.await(
                                                    3, java.util.concurrent.TimeUnit.SECONDS));
                                    assertThrows(
                                            java.util.concurrent.TimeoutException.class,
                                            () ->
                                                    future.get()
                                                            .get(
                                                                    200,
                                                                    java.util.concurrent.TimeUnit
                                                                            .MILLISECONDS));
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new AssertionError(interrupted);
                                }
                            });
            assertEquals("RW1", future.get().get(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void qmsDispatchHasAnInternalBoundaryAndPreservesAuditCredentials() throws Exception {
        var tasks = mock(MesTaskService.class);
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var target =
                new com.kitex.mom.mes.controller.MesTaskController(
                        tasks,
                        mock(MesTaskInWmsCoordinator.class),
                        mock(com.kitex.mom.mes.batch.BatchGeneratorService.class));
        var factory = new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(target);
        factory.addAspect(new com.kitex.common.security.component.MomSecurityInnerAspect(request));
        var controller = (com.kitex.mom.mes.controller.MesTaskController) factory.getProxy();
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> controller.addRework(dispatch()));
        var method =
                com.kitex.mom.mes.api.feign.RemoteMesTaskService.class.getMethod(
                        "addRework", com.kitex.mom.mes.dto.MesTaskReceiveDTO.class);
        assertNotNull(method.getAnnotation(com.kitex.common.feign.annotation.InternalCall.class));
        assertNull(method.getAnnotation(com.kitex.common.feign.annotation.NoToken.class));
    }

    private String status() {
        return db.queryForObject(
                "SELECT rework_callback_status FROM mes_task WHERE id=1", String.class);
    }

    private void table(MybatisConfiguration config, Class<?> type) {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(config, "rework-completion"), type);
        var info = TableInfoHelper.getTableInfo(type);
        StringBuilder ddl =
                new StringBuilder("CREATE TABLE " + info.getTableName() + "(id bigint primary key");
        for (var f : info.getFieldList()) {
            var t = f.getPropertyType();
            String sql =
                    t == Long.class || t == long.class
                            ? "bigint"
                            : t == Integer.class || t == int.class
                                    ? "int"
                                    : t == Boolean.class
                                            ? "boolean"
                                            : t == BigDecimal.class
                                                    ? "decimal(24,8)"
                                                    : t == LocalDateTime.class || t == Date.class
                                                            ? "timestamp"
                                                            : "varchar(2000000)";
            ddl.append(',').append(f.getColumn()).append(' ').append(sql);
            if (f.getColumn().equals("del_flag")) ddl.append(" DEFAULT '0'");
        }
        db.execute(ddl.append(')').toString());
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
        boolean lose, trackDispatch, loseOriginal;
        final AtomicInteger reads = new AtomicInteger();
        final java.util.concurrent.CountDownLatch sourceAttempt =
                new java.util.concurrent.CountDownLatch(1);

        public Object intercept(Invocation invocation) throws Throwable {
            var statement = (MappedStatement) invocation.getArgs()[0];
            if (trackDispatch
                    && Thread.currentThread().getName().startsWith("pool-")
                    && statement.getId().endsWith("MesTaskMapper.selectList")
                    && reads.incrementAndGet() == 2) sourceAttempt.countDown();
            var result = invocation.proceed();
            if (loseOriginal
                    && statement.getId().endsWith("MesReworkCompletionOutboxMapper.insert"))
                return 0;
            return lose && statement.getId().contains("MesTaskMapper.update") ? 0 : result;
        }
    }
}
