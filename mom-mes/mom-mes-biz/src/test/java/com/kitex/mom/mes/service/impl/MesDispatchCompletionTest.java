package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mes.dto.MesQualityGateDecision;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.*;
import com.kitex.mom.plan.feign.RemotePlnIssueService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 公开完工入口、实际持久化与事务；质量/工装/PLAN远端为受控端口。 */
class MesDispatchCompletionTest {
    private JdbcTemplate db;
    private SqlSessionTemplate sessions;
    private DataSourceTransactionManager transactions;
    private MesTaskArrangeService service;
    private final Map<Class<?>, Object> dependencies = new HashMap<>();
    private boolean planFails;
    private boolean qualityAllowed = true;

    @BeforeEach
    void setup() throws Exception {
        dependencies.clear();
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var actor = mock(MomUser.class);
        when(actor.getId()).thenReturn(7L);
        when(actor.getTenantId()).thenReturn(1L);
        when(actor.getUsername()).thenReturn("worker-1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, "N/A", List.of()));
        var source = new DriverManagerDataSource("jdbc:h2:mem:dispatch-completion-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        db = new JdbcTemplate(source);
        db.execute("CREATE SCHEMA mom_mes");
        db.execute("CREATE TABLE execution_lock(id int primary key)");
        db.update("INSERT INTO execution_lock VALUES(1)");
        db.execute("CREATE TABLE callback_audit(request_id varchar,issue_no varchar)");
        db.execute("CREATE TABLE workpiece_hook(arrange_id bigint)");
        var config = new MybatisConfiguration();
        var global = new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(new com.kitex.common.data.mybatis.MybatisPlusMetaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(config, global);
        for (Class<?> mapper : List.of(MesTaskArrangeMapper.class, MesProduceTaskMapper.class, MesTaskMapper.class,
                MesTaskExecutionSegmentMapper.class, MesToolingExecutionMapper.class)) config.addMapper(mapper);
        for (Class<?> entity : List.of(MesTaskArrangeEntity.class, MesProduceTaskEntity.class, MesTaskEntity.class,
                MesTaskExecutionSegmentEntity.class, MesToolingExecutionEntity.class)) createTable(config, entity);
        config.addMapper(MesDispatchCompletionMapper.class);
        config.addMapper(MesPlanCompletionMapper.class);
        config.addMapper(MesPlanCompletionOutboxMapper.class);
        for (Class<?> entity : List.of(MesPlanCompletionOutboxEntity.class, MesApsBatchAllocationEntity.class)) createTable(config, entity);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(config); factory.setGlobalConfig(global);
        var interceptors = new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
        interceptors.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor(
                new com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler() {
                    @Override public net.sf.jsqlparser.expression.Expression getTenantId() {
                        return new net.sf.jsqlparser.expression.LongValue(TenantContextHolder.getTenantId());
                    }
                }));
        factory.setPlugins(interceptors);
        sessions = new SqlSessionTemplate(factory.getObject());
        dependencies.put(MesToolingExecutionMapper.class, sessions.getMapper(MesToolingExecutionMapper.class));
        var actualFacts = sessions.getMapper(MesPlanCompletionMapper.class);
        var completionFacts = (MesPlanCompletionMapper) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{MesPlanCompletionMapper.class}, (proxy, method, args) -> {
                    if (method.getName().equals("lockEnqueue")) return db.queryForObject("SELECT id FROM execution_lock WHERE id=1 FOR UPDATE", Integer.class);
                    try { return method.invoke(actualFacts, args); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
        dependencies.put(MesPlanCompletionOutboxService.class, new MesPlanCompletionOutboxService(completionFacts,
                sessions.getMapper(MesPlanCompletionOutboxMapper.class), source, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()));
        dependencies.put(MesDispatchCompletionGuard.class,
                new MesDispatchCompletionGuard(sessions.getMapper(MesDispatchCompletionMapper.class), source));
        transactions = new DataSourceTransactionManager(source);
        var produce = new MesProduceTaskServiceImpl();
        ReflectionTestUtils.setField(produce, "baseMapper", sessions.getMapper(MesProduceTaskMapper.class));
        dependencies.put(MesProduceTaskService.class, produce);
        var taskConstructor = MesTaskServiceImpl.class.getDeclaredConstructors()[0];
        var tasks = (MesTaskServiceImpl) taskConstructor.newInstance(Arrays.stream(taskConstructor.getParameterTypes())
                .map(this::dependency).toArray());
        ReflectionTestUtils.setField(tasks, "baseMapper", sessions.getMapper(MesTaskMapper.class));
        var remote = mock(RemotePlnIssueService.class);
        when(remote.edit(anyList())).thenAnswer(call -> {
            if (planFails) throw new CheckedException("controlled PLAN unavailable");
            for (var row : (List<com.kitex.mom.oms.dto.OmsOrderLifeCycleDTO>) call.getArgument(0))
                db.update("INSERT INTO callback_audit VALUES(?,?)", row.getRequestId(), row.getNo());
            return R.ok(true);
        });
        ReflectionTestUtils.setField(tasks, "remotePlnIssueService", remote);
        dependencies.put(MesTaskService.class, tasks);
        var access = mock(MesActualFactoryAccessService.class);
        when(access.require("write")).thenReturn("F1");
        var groups = mock(MesIdentifierBindingGroupService.class);
        doAnswer(call -> { db.queryForObject("SELECT id FROM execution_lock WHERE id=1 FOR UPDATE", Integer.class); return null; })
                .when(groups).lockDispatchTask(anyString());
        dependencies.put(MesExecutionTaskLock.class, new MesExecutionTaskLock(access, groups,
                sessions.getMapper(MesTaskArrangeMapper.class), mock(MesTaskReportMapper.class)));
        dependencies.put(MesTaskExecutionSegmentMapper.class, sessions.getMapper(MesTaskExecutionSegmentMapper.class));
        var quality = dependency(MesQualityGateService.class);
        when(quality.checkFinish(any(), any())).thenAnswer(call -> new MesQualityGateDecision()
                .setAllowed(qualityAllowed).setPassed(qualityAllowed).setMessage("controlled quality gate"));
        when(dependency(MesQualityQuantityService.class).reconcile(anyString(), any())).thenAnswer(call -> call.getArgument(1));
        doAnswer(call -> { db.update("INSERT INTO workpiece_hook VALUES(?)", ((MesTaskArrangeEntity) call.getArgument(0)).getId()); return null; })
                .when(dependency(MesWorkpieceTimeService.class)).finishForArrange(any(), any(), anyString());
        var constructor = MesTaskArrangeServiceImpl.class.getDeclaredConstructors()[0];
        var arguments = new ArrayList<>();
        for (Class<?> type : constructor.getParameterTypes()) arguments.add(dependency(type));
        var target = (MesTaskArrangeServiceImpl) constructor.newInstance(arguments.toArray());
        ReflectionTestUtils.setField(target, "baseMapper", sessions.getMapper(MesTaskArrangeMapper.class));
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (MesTaskArrangeService) proxy.getProxy();
        db.update("INSERT INTO mes_task(id,tenant_id,factory_code,code,status,notice_num,plan_no,mat_code) VALUES(1,1,'F1','T1','PRODUCT',5,'ISSUE-1','MAT1')");
        operation(21L, "P1", "R1", true, "PRODUCT", "5");
        dispatch(11L, "A1", "P1", "R1", "PRODUCT");
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear(); FactoryContextHolder.clear(); SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRODUCT", "PAUSE", "RECEIVE", "UN_RECEIVE"})
    void finishingOneDispatchKeepsItsOtherDispatchAndParentsOpen(String otherStatus) {
        dispatch(12L, "A2", "P1", "R1", otherStatus);
        service.finish(11L);
        assertEquals("FINISH", state("mes_task_arrange", 11));
        assertEquals(otherStatus, state("mes_task_arrange", 12));
        assertEquals(otherStatus, state("mes_produce_task", 21));
        assertEquals("PRODUCT", state("mes_task", 1));
        assertEquals(0, count("mes_plan_completion_outbox"));
        assertNull(db.queryForObject("SELECT finish_time FROM mes_produce_task WHERE id=21", LocalDateTime.class));
        assertEquals(1, count("workpiece_hook"));
    }

    @Test
    void lastDispatchFinishesTheOperationAndUsesItsActualTerminalQuantity() {
        dispatch(12L, "A2", "P1", "R1", "FINISH");
        service.finish(11L);
        assertEquals("FINISH", state("mes_produce_task", 21));
        assertEquals("FINISH", state("mes_task", 1));
        assertEquals(0, new BigDecimal("5").compareTo(db.queryForObject("SELECT complete_num FROM mes_task WHERE id=1", BigDecimal.class)));
        assertEquals(1, count("mes_plan_completion_outbox"));
    }

    @Test
    void anotherUnfinishedOperationKeepsTheMainTaskOpen() {
        operation(22L, "P2", "R2", false, "PAUSE", "3");
        service.finish(11L);
        assertEquals("FINISH", state("mes_produce_task", 21));
        assertEquals("PRODUCT", state("mes_task", 1));
        assertEquals(0, count("mes_plan_completion_outbox"));
    }

    @Test
    void softDeletedActiveDispatchCannotDisappearFromCompletion() {
        dispatch(12L, "A2", "P1", "R1", "PRODUCT");
        db.update("UPDATE mes_task_arrange SET del_flag='1' WHERE id=12");
        service.finish(11L);
        assertEquals("PRODUCT", state("mes_produce_task", 21));
        assertEquals("PRODUCT", state("mes_task", 1));
        assertEquals(0, count("mes_plan_completion_outbox"));
    }

    @Test
    void anActiveDispatchOfAnotherAlreadyMarkedFinishedOperationPreventsTaskCompletion() {
        operation(22L, "P2", "R2", false, "FINISH", "5");
        dispatch(12L, "A2", "P2", "R2", "PAUSE");
        service.finish(11L);
        assertEquals("PRODUCT", state("mes_task", 1));
        assertEquals(0, count("mes_plan_completion_outbox"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALIEN", "NULL"})
    void unknownDispatchStateRollsBackTheFinish(String state) {
        dispatch(12L, "A2", "P1", "R1", "NULL".equals(state) ? null : state);
        assertThrows(CheckedException.class, () -> service.finish(11L));
        assertEquals("PRODUCT", state("mes_task_arrange", 11));
        assertEquals(0, count("workpiece_hook"));
        assertEquals(0, count("mes_plan_completion_outbox"));
    }

    @Test
    void missingTerminalFactCannotFallbackToTheCurrentOperationQuantity() {
        db.update("UPDATE mes_produce_task SET last_route=false WHERE id=21");
        assertThrows(CheckedException.class, () -> service.finish(11L));
        assertEquals("PRODUCT", state("mes_task_arrange", 11));
        assertEquals(0, count("workpiece_hook"));
    }

    @Test
    void qualityFailureLeavesAllLocalProductionFactsOpen() {
        qualityAllowed = false;
        assertThrows(CheckedException.class, () -> service.finish(11L));
        assertEquals("PRODUCT", state("mes_task_arrange", 11));
        assertEquals("PRODUCT", state("mes_produce_task", 21));
        assertEquals(0, count("workpiece_hook"));
    }

    @Test
    void localNotificationFailureRollsBackDispatchOperationTaskAndTimeHook() {
        db.execute("ALTER TABLE mes_plan_completion_outbox ADD CONSTRAINT reject_notice CHECK(status<>'PENDING')");
        assertThrows(RuntimeException.class, () -> service.finish(11L));
        assertEquals("PRODUCT", state("mes_task_arrange", 11));
        assertEquals("PRODUCT", state("mes_produce_task", 21));
        assertEquals("PRODUCT", state("mes_task", 1));
        assertEquals(0, count("workpiece_hook"));
        assertEquals(0, count("mes_task_execution_segment"));
    }

    @Test
    void remotePlanFailureDoesNotRollBackCommittedLocalCompletion() {
        planFails = true;
        service.finish(11L);
        assertEquals("FINISH", state("mes_task", 1));
        assertEquals("PENDING", db.queryForObject("SELECT status FROM mes_plan_completion_outbox", String.class));
        assertEquals(0, count("callback_audit"));
        assertEquals(1, count("workpiece_hook"));
    }

    @Test
    void stoppedPreviousDispatchDoesNotContributeOrHideAnActiveReplacement() {
        dispatch(12L, "OLD", "P1", "R1", "CLOSE");
        service.finish(11L);
        assertEquals("CLOSE", state("mes_task_arrange", 12));
        assertEquals("FINISH", state("mes_task", 1));
        assertEquals(0, new BigDecimal("5").compareTo(db.queryForObject("SELECT complete_num FROM mes_task WHERE id=1", BigDecimal.class)));
    }

    @Test
    void sameCodesInAnotherFactoryCannotBlockOrBeChangedByTheCurrentFinish() {
        operation(22L, "P1", "R1", true, "PRODUCT", "5");
        dispatch(12L, "A1", "P1", "R1", "PRODUCT");
        db.update("UPDATE mes_produce_task SET factory_code='F2' WHERE id=22");
        db.update("UPDATE mes_task_arrange SET factory_code='F2' WHERE id=12");
        db.update("INSERT INTO mes_task(id,tenant_id,factory_code,code,status,notice_num,plan_no,mat_code) VALUES(2,1,'F2','T1','PRODUCT',5,'ISSUE-1','MAT1')");
        assertDoesNotThrow(() -> service.finish(11L));
        assertEquals("FINISH", state("mes_task", 1));
        assertEquals("PRODUCT", state("mes_task", 2));
        assertEquals("PRODUCT", state("mes_produce_task", 22));
        assertEquals("PRODUCT", state("mes_task_arrange", 12));
    }

    @Test
    void sameCodesInAnotherTenantDoNotParticipateInCompletion() {
        operation(22L, "P1", "R1", true, "PRODUCT", "5");
        dispatch(12L, "A1", "P1", "R1", "PRODUCT");
        db.update("UPDATE mes_produce_task SET tenant_id=2 WHERE id=22");
        db.update("UPDATE mes_task_arrange SET tenant_id=2 WHERE id=12");
        service.finish(11L);
        assertEquals("FINISH", state("mes_task", 1));
        assertEquals("PRODUCT", state("mes_produce_task", 22));
        assertEquals("PRODUCT", state("mes_task_arrange", 12));
    }

    @Test
    void aSoftDeletedUnfinishedOperationStillPreventsMainTaskCompletion() {
        operation(22L, "P2", "R2", false, "PAUSE", "3");
        db.update("UPDATE mes_produce_task SET del_flag='1' WHERE id=22");
        service.finish(11L);
        assertEquals("PRODUCT", state("mes_task", 1));
        assertEquals(0, count("mes_plan_completion_outbox"));
    }

    @Test
    void legacyTerminalQuantitiesUseTheMinimumInsteadOfAddingOperationOutput() {
        operation(22L, "P2", "R2", true, "FINISH", "3");
        service.finish(11L);
        assertEquals(0, new BigDecimal("3").compareTo(db.queryForObject("SELECT complete_num FROM mes_task WHERE id=1", BigDecimal.class)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "6", "NULL"})
    void invalidTerminalQuantityCannotManufactureACompletedTask(String qty) {
        db.update("UPDATE mes_produce_task SET complete_num=? WHERE id=21", "NULL".equals(qty) ? null : new BigDecimal(qty));
        assertThrows(CheckedException.class, () -> service.finish(11L));
        assertEquals("PRODUCT", state("mes_task_arrange", 11));
        assertEquals("PRODUCT", state("mes_task", 1));
    }

    @Test
    void staleRepeatableReadTransactionCannotDecideCompletionAfterTheLock() {
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        assertThrows(CheckedException.class, () -> transaction.executeWithoutResult(status -> service.finish(11L)));
        assertEquals("PRODUCT", state("mes_task_arrange", 11));
        assertEquals(0, count("workpiece_hook"));
    }

    @Test
    void duplicatedMainProcessCodesRemainSeparateStableNodes() {
        operation(22L, "P2", "R1", true, "PRODUCT", "5");
        dispatch(12L, "A2", "P2", "R1", "PRODUCT");
        db.update("UPDATE mes_produce_task SET route_execution_schema_version='NODE_EXECUTION_V1',"
                + "source_route_node_code=CASE id WHEN 21 THEN 'RN-21' ELSE 'RN-22' END,"
                + "predecessor_node_codes=CASE id WHEN 21 THEN '' ELSE 'RN-21' END,"
                + "successor_node_codes=CASE id WHEN 21 THEN 'RN-22' ELSE '' END,"
                + "predecessor_codes=CASE id WHEN 21 THEN '' ELSE 'R1' END,"
                + "successor_codes=CASE id WHEN 21 THEN 'R1' ELSE '' END,last_route=(id=22)");
        db.update("UPDATE mes_task_arrange SET route_execution_schema_version='NODE_EXECUTION_V1',"
                + "source_route_node_code=CASE id WHEN 11 THEN 'RN-21' ELSE 'RN-22' END,"
                + "predecessor_node_codes=CASE id WHEN 11 THEN '' ELSE 'RN-21' END,"
                + "successor_node_codes=CASE id WHEN 11 THEN 'RN-22' ELSE '' END,"
                + "predecessor_codes=CASE id WHEN 11 THEN '' ELSE 'R1' END,"
                + "successor_codes=CASE id WHEN 11 THEN 'R1' ELSE '' END,last_route=(id=12)");
        service.finish(11L);
        assertEquals("FINISH", state("mes_produce_task", 21));
        assertEquals("PRODUCT", state("mes_produce_task", 22));
        assertEquals("PRODUCT", state("mes_task", 1));
        service.finish(12L);
        assertEquals("FINISH", state("mes_task", 1));
        assertEquals(1, count("mes_plan_completion_outbox"));
    }

    @Test
    void wrongStableNodeInPeerDispatchRollsBackAllLocalFinishFacts() {
        dispatch(12L, "A2", "P1", "OTHER-ROUTE", "FINISH");
        assertThrows(CheckedException.class, () -> service.finish(11L));
        assertEquals("PRODUCT", state("mes_task_arrange", 11));
        assertEquals(0, count("workpiece_hook"));
    }

    @Test
    void concurrentLastDispatchesOnlyCompleteTheParentAndNotifyPlanOnce() throws Exception {
        dispatch(12L, "A2", "P1", "R1", "PRODUCT");
        var actor = SecurityContextHolder.getContext().getAuthentication();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (long id : List.of(11L, 12L)) results.add(pool.submit(() -> {
                TenantContextHolder.setTenantId(1L); FactoryContextHolder.setFactoryCode("F1");
                SecurityContextHolder.getContext().setAuthentication(actor);
                try {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    service.finish(id);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
                } finally {
                    TenantContextHolder.clear(); FactoryContextHolder.clear(); SecurityContextHolder.clearContext();
                }
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            for (var result : results) result.get(10, TimeUnit.SECONDS);
        } finally { start.countDown(); pool.shutdownNow(); }
        assertEquals("FINISH", state("mes_task_arrange", 11));
        assertEquals("FINISH", state("mes_task_arrange", 12));
        assertEquals("FINISH", state("mes_task", 1));
        assertEquals(1, count("mes_plan_completion_outbox"));
        assertEquals(2, count("workpiece_hook"));
    }

    @Test
    void lateQualityReleaseCannotBeOverwrittenByTheFinishStatusUpdate() {
        db.update("UPDATE mes_produce_task SET quality_released_num=2 WHERE id=21");
        doAnswer(call -> {
            db.update("INSERT INTO workpiece_hook VALUES(11)");
            db.update("UPDATE mes_produce_task SET quality_released_num=5 WHERE id=21");
            return null;
        }).when(dependency(MesWorkpieceTimeService.class)).finishForArrange(any(), any(), anyString());
        service.finish(11L);
        assertEquals(0, new BigDecimal("5").compareTo(db.queryForObject("SELECT quality_released_num FROM mes_produce_task WHERE id=21", BigDecimal.class)));
        assertEquals("FINISH", state("mes_task", 1));
    }

    @Test
    void finishingThenClosingTheLastActivePeerHasTheSameResultAsTheOppositeOrder() {
        dispatch(12L, "A2", "P1", "R1", "PRODUCT");
        service.finish(11L);
        assertEquals("PRODUCT", state("mes_task", 1));
        service.close(12L);
        assertEquals("FINISH", state("mes_task_arrange", 11));
        assertEquals("CLOSE", state("mes_task_arrange", 12));
        assertEquals("FINISH", state("mes_produce_task", 21));
        assertEquals("FINISH", state("mes_task", 1));
        assertEquals(1, count("mes_plan_completion_outbox"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLOSE", "FINISH"})
    void terminalParentOperationCannotBeReopenedByAResidualDispatch(String state) {
        db.update("UPDATE mes_produce_task SET status=? WHERE id=21", state);
        assertThrows(CheckedException.class, () -> service.finish(11L));
        assertEquals(state, state("mes_produce_task", 21));
        assertEquals("PRODUCT", state("mes_task_arrange", 11));
        assertEquals(0, count("mes_plan_completion_outbox"));
    }

    @Test
    void actualMainTaskCloseCannotBeUndoneByFinishingItsResidualDispatch() {
        dependency(MesTaskService.class).close(1L);
        assertThrows(CheckedException.class, () -> service.finish(11L));
        assertEquals("CLOSE", state("mes_task", 1));
        assertEquals("PRODUCT", state("mes_task_arrange", 11));
        assertEquals(0, count("mes_plan_completion_outbox"));
    }

    @Test
    void alreadyFinishedMainTaskCannotBeFinishedAgainByAResidualDispatch() {
        db.update("UPDATE mes_task SET status='FINISH' WHERE id=1");
        assertThrows(CheckedException.class, () -> service.finish(11L));
        assertEquals("FINISH", state("mes_task", 1));
        assertEquals("PRODUCT", state("mes_task_arrange", 11));
        assertEquals(0, count("mes_plan_completion_outbox"));
    }

    @Test
    void closingEveryDispatchWithoutAnyFinishDoesNotManufactureCompletion() {
        dispatch(12L, "A2", "P1", "R1", "PRODUCT");
        service.close(11L); service.close(12L);
        assertNotEquals("FINISH", state("mes_produce_task", 21));
        assertEquals("PRODUCT", state("mes_task", 1));
        assertEquals(0, count("mes_plan_completion_outbox"));
    }

    @Test
    void closingResidualWorkAfterTheMainTaskWasClosedDoesNotReviveItsParents() {
        dispatch(12L, "A2", "P1", "R1", "PRODUCT");
        service.finish(11L);
        dependency(MesTaskService.class).close(1L);
        service.close(12L);
        assertEquals("CLOSE", state("mes_task", 1));
        assertEquals("CLOSE", state("mes_task_arrange", 12));
        assertEquals(0, count("mes_plan_completion_outbox"));
    }

    @Test
    void automaticCompletionOnCloseStillRequiresTheFinishQualityGate() {
        dispatch(12L, "A2", "P1", "R1", "PRODUCT");
        service.finish(11L);
        qualityAllowed = false;
        assertThrows(CheckedException.class, () -> service.close(12L));
        assertEquals("PRODUCT", state("mes_task_arrange", 12));
        assertEquals("PRODUCT", state("mes_task", 1));
        assertEquals(1, count("workpiece_hook"));
        assertEquals(0, count("mes_plan_completion_outbox"));
        qualityAllowed = true;
        service.close(12L);
        assertEquals("FINISH", state("mes_task", 1));
        assertEquals(1, count("mes_plan_completion_outbox"));
    }

    @Test
    void localNotificationFailureDuringFinalCloseRollsBackOnlyThisTerminationAttempt() {
        dispatch(12L, "A2", "P1", "R1", "PRODUCT");
        service.finish(11L);
        db.execute("ALTER TABLE mes_plan_completion_outbox ADD CONSTRAINT reject_notice CHECK(status<>'PENDING')");
        assertThrows(RuntimeException.class, () -> service.close(12L));
        assertEquals("FINISH", state("mes_task_arrange", 11));
        assertEquals("PRODUCT", state("mes_task_arrange", 12));
        assertEquals("PRODUCT", state("mes_task", 1));
        assertEquals(1, count("workpiece_hook"));
        db.execute("ALTER TABLE mes_plan_completion_outbox DROP CONSTRAINT reject_notice");
        service.close(12L);
        assertEquals("FINISH", state("mes_task", 1));
        assertEquals(1, count("mes_plan_completion_outbox"));
    }

    @Test
    void aMainTaskClosedAfterTheInitialReadCannotBeOverwrittenByFinish() {
        doAnswer(call -> {
            var anotherTransaction = new TransactionTemplate(transactions);
            anotherTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            anotherTransaction.executeWithoutResult(status -> dependency(MesTaskService.class).close(1L));
            db.update("INSERT INTO workpiece_hook VALUES(11)");
            return null;
        }).when(dependency(MesWorkpieceTimeService.class)).finishForArrange(any(), any(), anyString());
        assertThrows(CheckedException.class, () -> service.finish(11L));
        assertEquals("CLOSE", state("mes_task", 1));
        assertEquals("PRODUCT", state("mes_task_arrange", 11));
        assertEquals(0, count("mes_plan_completion_outbox"));
        assertEquals(0, count("workpiece_hook"));
    }

    private void operation(long id, String code, String route, boolean last, String status, String qty) {
        db.update("INSERT INTO mes_produce_task(id,tenant_id,factory_code,code,task_code,route_code,process_route_code,last_route,status,complete_num,quality_released_num,notice_num) VALUES(?,1,'F1',?,'T1',?,'ROUTE-1',?,?,?,?,5)",
                id, code, route, last, status, new BigDecimal(qty), new BigDecimal(qty));
    }

    private void dispatch(long id, String code, String process, String route, String status) {
        db.update("INSERT INTO mes_task_arrange(id,tenant_id,factory_code,code,task_code,produce_task_code,route_code,worker_code,status,start_time) VALUES(?,1,'F1',?,'T1',?,?,'worker-1',?,?)",
                id, code, process, route, status, LocalDateTime.now().minusMinutes(2));
    }

    private String state(String table, long id) { return db.queryForObject("SELECT status FROM " + table + " WHERE id=?", String.class, id); }
    private int count(String table) { return db.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private <T> T dependency(Class<T> type) { return type.cast(dependencies.computeIfAbsent(type, ignored -> mock(type))); }

    private void createTable(MybatisConfiguration config, Class<?> type) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(config, "completion-ddl"), type);
        var info = TableInfoHelper.getTableInfo(type);
        var ddl = new StringBuilder("CREATE TABLE ").append(info.getTableName()).append("(id bigint primary key");
        for (var field : info.getFieldList()) {
            Class<?> t = field.getPropertyType();
            String sql = t == Long.class ? "bigint" : t == Integer.class ? "integer" : t == Boolean.class ? "boolean"
                    : t == BigDecimal.class ? "decimal(24,8)" : t == LocalDateTime.class || t == java.util.Date.class ? "timestamp" : "varchar(64000)";
            ddl.append(',').append(field.getColumn()).append(' ').append(sql);
            if (field.getColumn().equals("del_flag")) ddl.append(" default '0'");
        }
        db.execute(ddl.append(')').toString());
        db.execute("CREATE SYNONYM mom_mes." + info.getTableName() + " FOR PUBLIC." + info.getTableName());
    }
}
