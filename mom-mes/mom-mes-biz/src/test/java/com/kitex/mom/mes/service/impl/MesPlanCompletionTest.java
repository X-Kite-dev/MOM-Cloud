package com.kitex.mom.mes.service.impl;

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
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.MesTaskService;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** 实际反馈入口必须只写本域永久通知，PLAN可离线。 */
class MesPlanCompletionTest {
    private JdbcTemplate db;
    private MesTaskService service;
    private TransactionTemplate transaction;
    private MesTaskEntity task;
    private MesPlanCompletionOutboxService outboxes;
    private MesPlanCompletionProgressService progress;
    private MesPlanCompletionOutboxMapper outboxRows;
    private MesPlanCompletionWorker worker;
    private MesPlanCompletionRetryService retries;
    private com.kitex.mom.mes.task.MesPlanCompletionJob job;
    private boolean loseResponse;
    private boolean badAcknowledgement;
    private final Map<String, com.kitex.mom.plan.dto.PlanMesCompletionDTO.Receipt> receiver =
            new HashMap<>();
    private final Map<Class<?>, Object> dependencies = new HashMap<>();

    @BeforeEach
    void setup() throws Exception {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:mes-completion-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        source.setDriverClassName("org.h2.Driver");
        db = new JdbcTemplate(source);
        var config = new MybatisConfiguration();
        var global = new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(
                new com.kitex.common.data.mybatis.MybatisPlusMetaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(config, global);
        config.addMapper(MesTaskMapper.class);
        config.addMapper(MesPlanCompletionMapper.class);
        config.addMapper(MesPlanCompletionOutboxMapper.class);
        config.addMapper(MesProductionOrderMapper.class);
        table(config, MesTaskEntity.class);
        table(config, MesPlanCompletionOutboxEntity.class);
        table(config, MesApsBatchAllocationEntity.class);
        table(config, MesPlanWithdrawalReceiptEntity.class);
        db.execute("CREATE SCHEMA mom_mes");
        for (String name :
                List.of(
                        "mes_task",
                        "mes_plan_completion_outbox",
                        "mes_aps_batch_allocation",
                        "mes_plan_withdrawal_receipt"))
            db.execute("CREATE SYNONYM mom_mes." + name + " FOR PUBLIC." + name);
        db.execute("CREATE TABLE enqueue_lock(id int primary key)");
        db.update("INSERT INTO enqueue_lock VALUES(1)");
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
        var actual = sessions.getMapper(MesPlanCompletionMapper.class);
        var facts =
                (MesPlanCompletionMapper)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {MesPlanCompletionMapper.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("lockEnqueue"))
                                        return db.queryForObject(
                                                "SELECT id FROM enqueue_lock WHERE id=1 FOR UPDATE",
                                                Integer.class);
                                    try {
                                        return method.invoke(actual, args);
                                    } catch (java.lang.reflect.InvocationTargetException e) {
                                        throw e.getCause();
                                    }
                                });
        outboxRows = sessions.getMapper(MesPlanCompletionOutboxMapper.class);
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        outboxes = new MesPlanCompletionOutboxService(facts, outboxRows, source, json);
        progress =
                new MesPlanCompletionProgressService(
                        facts,
                        outboxes,
                        new MesProductionOrderCapacityGuard(
                                sessions.getMapper(MesProductionOrderMapper.class), source),
                        json);
        dependencies.put(MesPlanCompletionOutboxService.class, outboxes);
        var remote = dependency(RemotePlnIssueService.class);
        when(remote.edit(anyList())).thenReturn(R.ok(true));
        var target = construct(MesTaskServiceImpl.class);
        ReflectionTestUtils.setField(target, "baseMapper", sessions.getMapper(MesTaskMapper.class));
        var manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        var completionRemote = mock(com.kitex.mom.plan.feign.RemotePlnMesCompletionService.class);
        when(completionRemote.reconcile(any()))
                .thenAnswer(
                        call -> {
                            var command =
                                    (com.kitex.mom.plan.dto.PlanMesCompletionDTO.Command)
                                            call.getArgument(0);
                            assertEquals(
                                    command.getFactoryCode(),
                                    FactoryContextHolder.getFactoryCode());
                            var receipt =
                                    receiver.computeIfAbsent(
                                            command.getEventId(),
                                            key ->
                                                    new com.kitex.mom.plan.dto.PlanMesCompletionDTO
                                                                    .Receipt()
                                                            .setEventId(command.getEventId())
                                                            .setOutboxId(command.getOutboxId())
                                                            .setPayloadFingerprint(
                                                                    command.getPayloadFingerprint())
                                                            .setReceiptIds(List.of(501L)));
                            if (loseResponse) {
                                loseResponse = false;
                                throw new IllegalStateException(
                                        "response lost after remote commit");
                            }
                            if (badAcknowledgement)
                                return R.ok(
                                        new com.kitex.mom.plan.dto.PlanMesCompletionDTO.Receipt()
                                                .setEventId("OTHER")
                                                .setReceiptIds(List.of(501L)));
                            return R.ok(receipt);
                        });
        worker = proxy(new MesPlanCompletionWorker(outboxRows, completionRemote, json), manager);
        retries = proxy(new MesPlanCompletionRetryService(outboxRows), manager);
        job = new com.kitex.mom.mes.task.MesPlanCompletionJob(outboxRows, worker, retries);
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = (MesTaskService) proxy.getProxy();
        task =
                new MesTaskEntity()
                        .setCode("TASK-1")
                        .setPlanNo("PLAN-1")
                        .setMatCode("MAT1")
                        .setFactoryCode("F1")
                        .setStatus("FINISH")
                        .setNoticeNum(new BigDecimal("2"))
                        .setCompleteNum(new BigDecimal("2"));
        task.setId(1L);
        task.setTenantId(1L);
        db.update(
                "INSERT INTO"
                    + " mes_task(id,tenant_id,factory_code,code,plan_no,mat_code,status,notice_num,complete_num,completion_version,finish_time)"
                    + " VALUES(1,1,'F1','TASK-1','PLAN-1','MAT1','FINISH',2,2,0,TIMESTAMP"
                    + " '2026-10-24 08:00:00')");
    }

    @Test
    void completionCreatesAnImmutableLocalNotification() {
        service.feedbackPlanCompletion(task, new BigDecimal("2"));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM mes_plan_completion_outbox", Integer.class));
    }

    @Test
    void planOfflineCannotUndoTheLocalCompletion() {
        when(dependency(RemotePlnIssueService.class).edit(anyList()))
                .thenThrow(new IllegalStateException("PLAN offline"));
        assertDoesNotThrow(() -> service.feedbackPlanCompletion(task, new BigDecimal("2")));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM mes_plan_completion_outbox", Integer.class));
    }

    @Test
    void localRollbackAlsoRollsBackTheNotification() {
        assertThrows(
                CheckedException.class,
                () ->
                        transaction.executeWithoutResult(
                                status -> {
                                    service.feedbackPlanCompletion(task, new BigDecimal("2"));
                                    throw new CheckedException("rollback");
                                }));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM mes_plan_completion_outbox", Integer.class));
    }

    private MesPlanCompletionOutboxEntity enqueue() {
        return transaction.execute(status -> outboxes.enqueue(task, new BigDecimal("2")));
    }

    private void secondTask(String state, String quantity, Long tenant, String factory) {
        db.update(
                "INSERT INTO"
                    + " mes_task(id,tenant_id,factory_code,code,plan_no,mat_code,status,notice_num,complete_num,completion_version)"
                    + " VALUES(2,?,?,'TASK-2','PLAN-1','MAT1',?,3,?,0)",
                tenant,
                factory,
                state,
                new BigDecimal(quantity));
        if ("FINISH".equals(state))
            db.update("UPDATE mes_task SET finish_time=TIMESTAMP '2026-10-24 09:00:00' WHERE id=2");
    }

    @Test
    void repeatedSameGenerationKeepsTheExactOriginalPayload() {
        var first = enqueue();
        var second = enqueue();
        assertEquals(first.getId(), second.getId());
        assertEquals(first.getPayloadJson(), second.getPayloadJson());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM mes_plan_completion_outbox", Integer.class));
    }

    @Test
    void changedActualQuantityInTheSameGenerationIsRefused() {
        enqueue();
        db.update("UPDATE mes_task SET complete_num=1 WHERE id=1");
        assertThrows(
                CheckedException.class,
                () ->
                        transaction.executeWithoutResult(
                                status -> outboxes.enqueue(task, BigDecimal.ONE)));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM mes_plan_completion_outbox", Integer.class));
    }

    @Test
    void firstSplitTaskIsNotTheCompletePlan() {
        var row = enqueue();
        secondTask("PRODUCT", "0", 1L, "F1");
        var value = progress.progress(row.getId(), 101L, "PLAN-1", "F1");
        assertFalse(value.getAllFinished());
        assertEquals(2, value.getTaskCount());
        assertEquals(0, new BigDecimal("5").compareTo(value.getAllocatedQuantity()));
        assertEquals(0, new BigDecimal("2").compareTo(value.getCompletedQuantity()));
    }

    @Test
    void allFinishedSplitTasksAggregateTheirActualQuantities() {
        var row = enqueue();
        secondTask("FINISH", "3", 1L, "F1");
        var value = progress.progress(row.getId(), 101L, "PLAN-1", "F1");
        assertTrue(value.getAllFinished());
        assertEquals(0, new BigDecimal("5").compareTo(value.getCompletedQuantity()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLOSE", "PRODUCT", "DISPATCH"})
    void stoppedOrPendingPeerCannotDisappearFromTheFullSource(String state) {
        var row = enqueue();
        secondTask(state, "0", 1L, "F1");
        db.update("UPDATE mes_task SET del_flag='1' WHERE id=2");
        var value = progress.progress(row.getId(), 101L, "PLAN-1", "F1");
        assertFalse(value.getAllFinished());
        assertEquals(2, value.getTaskCount());
        assertEquals(0, new BigDecimal("5").compareTo(value.getAllocatedQuantity()));
    }

    @Test
    void foreignTenantAndFactoryDoNotContributeToProgress() {
        var row = enqueue();
        secondTask("PRODUCT", "0", 2L, "F1");
        var value = progress.progress(row.getId(), 101L, "PLAN-1", "F1");
        assertEquals(1, value.getTaskCount());
        db.update("UPDATE mes_task SET tenant_id=1,factory_code='F2' WHERE id=2");
        value = progress.progress(row.getId(), 101L, "PLAN-1", "F1");
        assertEquals(1, value.getTaskCount());
        assertTrue(value.getAllFinished());
    }

    @Test
    void alteredEventPayloadIsNeverEvidence() {
        var row = enqueue();
        db.update(
                "UPDATE mes_plan_completion_outbox SET payload_json='{}' WHERE id=?", row.getId());
        assertThrows(CheckedException.class, () -> outboxes.evidence(row.getId(), "F1"));
    }

    @Test
    void eventCannotBeUsedToQueryAnotherPlan() {
        var row = enqueue();
        assertThrows(
                CheckedException.class,
                () -> progress.progress(row.getId(), 102L, "PLAN-OTHER", "F1"));
    }

    @Test
    void unknownPeerStatusIsNotInterpretedAsComplete() {
        var row = enqueue();
        secondTask("ALIEN", "0", 1L, "F1");
        assertThrows(
                CheckedException.class, () -> progress.progress(row.getId(), 101L, "PLAN-1", "F1"));
    }

    @Test
    void sourceReadRequiresTheActualFactoryContext() {
        var row = enqueue();
        FactoryContextHolder.setFactoryCode("F2");
        assertThrows(CheckedException.class, () -> outboxes.evidence(row.getId(), "F1"));
    }

    @Test
    void aCommittedNotificationIsDeliveredAndItsReceiptIsPermanent() {
        var row = enqueue();
        assertTrue(worker.deliverOne(row.getId(), "F1"));
        assertEquals("DELIVERED", outboxRows.selectById(row.getId()).getStatus());
        assertNull(outboxRows.selectById(row.getId()).getNextAttemptTime());
        assertFalse(worker.deliverOne(row.getId(), "F1"));
        assertEquals(1, receiver.size());
    }

    @Test
    void lostRemoteResponseRetriesTheSameEventAndKeepsTheOriginalReceipt() {
        var row = enqueue();
        loseResponse = true;
        assertThrows(IllegalStateException.class, () -> worker.deliverOne(row.getId(), "F1"));
        assertEquals("PENDING", outboxRows.selectById(row.getId()).getStatus());
        retries.mark(row.getId(), "F1", new IllegalStateException("offline"));
        assertEquals("RETRY_WAIT", outboxRows.selectById(row.getId()).getStatus());
        db.update(
                "UPDATE mes_plan_completion_outbox SET next_attempt_time=TIMESTAMP '2026-01-01"
                        + " 00:00:00' WHERE id=?",
                row.getId());
        assertTrue(worker.deliverOne(row.getId(), "F1"));
        assertEquals(1, receiver.size());
        assertEquals("DELIVERED", outboxRows.selectById(row.getId()).getStatus());
        assertNull(outboxRows.selectById(row.getId()).getLastError());
        assertEquals(2, outboxRows.selectById(row.getId()).getAttemptCount());
    }

    @Test
    void invalidRemoteAcknowledgementNeverMarksTheNotificationDelivered() {
        var row = enqueue();
        badAcknowledgement = true;
        assertThrows(CheckedException.class, () -> worker.deliverOne(row.getId(), "F1"));
        assertEquals("PENDING", outboxRows.selectById(row.getId()).getStatus());
    }

    @Test
    void localReceiptSaveFailureDoesNotDuplicateTheRemoteEffect() {
        var row = enqueue();
        db.execute(
                "ALTER TABLE mes_plan_completion_outbox ADD CONSTRAINT fail_delivery"
                        + " CHECK(status<>'DELIVERED')");
        assertThrows(RuntimeException.class, () -> worker.deliverOne(row.getId(), "F1"));
        assertEquals("PENDING", outboxRows.selectById(row.getId()).getStatus());
        assertEquals(1, receiver.size());
        db.execute("ALTER TABLE mes_plan_completion_outbox DROP CONSTRAINT fail_delivery");
        assertTrue(worker.deliverOne(row.getId(), "F1"));
        assertEquals(1, receiver.size());
    }

    @Test
    void backgroundDispatchRestoresTheCallingTenantFactoryAndSkipFlags() {
        enqueue();
        TenantContextHolder.setTenantId(99L);
        TenantContextHolder.setTenantSkip();
        FactoryContextHolder.setFactoryCode("PREVIOUS");
        FactoryContextHolder.setFactorySkip();
        assertEquals(1, job.dispatchDue(10));
        assertEquals(99L, TenantContextHolder.getTenantId());
        assertTrue(TenantContextHolder.getTenantSkip());
        assertEquals("PREVIOUS", FactoryContextHolder.getFactoryCode());
        assertTrue(FactoryContextHolder.getFactorySkip());
    }

    private void allocation(
            long taskId,
            long id,
            int seq,
            long issueId,
            String no,
            String quantity,
            String snapshot) {
        db.update(
                "UPDATE mes_task SET"
                    + " aps_task_id=100,aps_batch_id=?,aps_batch_no=?,aps_batch_fingerprint=? WHERE"
                    + " id=?",
                taskId,
                "BATCH-" + taskId,
                "f".repeat(64),
                taskId);
        db.update(
                "INSERT INTO"
                    + " mes_aps_batch_allocation(id,tenant_id,factory_code,mes_task_id,mes_task_code,aps_task_id,aps_batch_id,aps_batch_no,aps_batch_fingerprint,allocation_seq,issue_id,issue_no,allocated_quantity,issue_quantity_snapshot,completed_quantity)"
                    + " VALUES(?,1,'F1',?,?,100,?,?,?,?,?,?,?,?,0)",
                id,
                taskId,
                "TASK-" + taskId,
                taskId,
                "BATCH-" + taskId,
                "f".repeat(64),
                seq,
                issueId,
                no,
                new BigDecimal(quantity),
                new BigDecimal(snapshot));
    }

    @Test
    void apsSplitTasksUseTheFullSourceLedgerAndFreezeBoundedActualQuantities() {
        allocation(1, 11, 1, 7, "PLAN-1", "2", "5");
        secondTask("FINISH", "3", 1L, "F1");
        allocation(2, 12, 1, 7, "PLAN-1", "3", "5");
        var first = enqueue();
        task.setId(2L);
        var second = task.setCode("TASK-2");
        transaction.executeWithoutResult(status -> outboxes.enqueue(second, new BigDecimal("3")));
        var actual = progress.progress(first.getId(), 7L, "PLAN-1", "F1");
        assertTrue(actual.getAllFinished());
        assertEquals(2, actual.getTaskCount());
        assertEquals(0, new BigDecimal("5").compareTo(actual.getAllocatedQuantity()));
        assertEquals(0, new BigDecimal("5").compareTo(actual.getCompletedQuantity()));
    }

    @Test
    void roundingAcrossManyApsSourcesNeverCreatesANegativeFinalAllocation() {
        db.update("UPDATE mes_task SET notice_num=4,complete_num=0.00000002 WHERE id=1");
        for (int i = 1; i <= 4; i++) allocation(1, 10 + i, i, 100 + i, "P-" + i, "1", "1");
        var row =
                transaction.execute(status -> outboxes.enqueue(task, new BigDecimal("0.00000002")));
        var lines = outboxes.evidence(row.getId(), "F1").getEvent().getSources();
        assertEquals(4, lines.size());
        assertTrue(lines.stream().allMatch(line -> line.getCompletedQuantity().signum() >= 0));
        assertEquals(
                0,
                new BigDecimal("0.00000002")
                        .compareTo(
                                lines.stream()
                                        .map(
                                                com.kitex.mom.mes.dto.MesPlanCompletionDTO.Line
                                                        ::getCompletedQuantity)
                                        .reduce(BigDecimal.ZERO, BigDecimal::add)));
    }

    @Test
    void anUnprovedClosedApsTaskStillOccupiesTheSourcePlan() {
        allocation(1, 11, 1, 7, "PLAN-1", "2", "5");
        secondTask("CLOSE", "0", 1L, "F1");
        allocation(2, 12, 1, 7, "PLAN-1", "3", "5");
        var actual = progress.progress(enqueue().getId(), 7L, "PLAN-1", "F1");
        assertFalse(actual.getAllFinished());
        assertEquals(2, actual.getTaskCount());
        assertEquals(0, new BigDecimal("5").compareTo(actual.getAllocatedQuantity()));
    }

    private void withdrawal() {
        db.update(
                "INSERT INTO"
                    + " mes_plan_withdrawal_receipt(id,tenant_id,factory_code,issue_id,issue_no,request_id,request_fingerprint,expected_quantity,expected_evidence_fingerprint,actual_evidence_fingerprint,qms_evidence_fingerprint,status,handled_time,closed_produce_task_count,closed_arrange_count,cancelled_qms_task_count,closed_task_count,closed_task_codes,reason,message)"
                    + " VALUES(90,1,'F1',7,'PLAN-1','WITHDRAW-90',?,3,?,?,?,'SUCCEEDED',CURRENT_TIMESTAMP,0,0,0,1,'TASK-2','test"
                    + " withdrawal','completed')",
                "a".repeat(64),
                "b".repeat(64),
                "b".repeat(64),
                "c".repeat(64));
    }

    @Test
    void onlyACompleteSuccessfulWithdrawalCanReleaseTheCancelledSource() {
        allocation(1, 11, 1, 7, "PLAN-1", "2", "5");
        secondTask("CLOSE", "0", 1L, "F1");
        allocation(2, 12, 1, 7, "PLAN-1", "3", "5");
        withdrawal();
        var actual = progress.progress(enqueue().getId(), 7L, "PLAN-1", "F1");
        assertTrue(actual.getAllFinished());
        assertEquals(1, actual.getTaskCount());
        assertEquals(0, new BigDecimal("2").compareTo(actual.getAllocatedQuantity()));
    }

    @Test
    void aWithdrawalWithExecutionFactsCannotHideTheCancelledTask() {
        allocation(1, 11, 1, 7, "PLAN-1", "2", "5");
        secondTask("CLOSE", "0", 1L, "F1");
        allocation(2, 12, 1, 7, "PLAN-1", "3", "5");
        withdrawal();
        db.update("UPDATE mes_task SET start_time=CURRENT_TIMESTAMP WHERE id=2");
        var notice = enqueue();
        assertThrows(
                CheckedException.class,
                () -> progress.progress(notice.getId(), 7L, "PLAN-1", "F1"));
    }

    @Test
    void reopeningAndCompletingAgainCreatesANewGenerationWithoutChangingTheOriginal() {
        var original = enqueue();
        db.update("UPDATE mes_task SET completion_version=1,complete_num=1 WHERE id=1");
        var next = transaction.execute(status -> outboxes.enqueue(task, BigDecimal.ONE));
        assertNotEquals(original.getId(), next.getId());
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT count(*) FROM mes_plan_completion_outbox", Integer.class));
        assertEquals(
                0,
                new BigDecimal("2")
                        .compareTo(
                                outboxes.evidence(original.getId(), "F1")
                                        .getEvent()
                                        .getCompletedQuantity()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_FACTORY", "OTHER_TENANT", "TENANT_SKIP", "FACTORY_SKIP"})
    void callerScopeCannotReadAnotherOrganizationsEvidence(String kind) {
        var id = enqueue().getId();
        switch (kind) {
            case "NO_FACTORY" -> FactoryContextHolder.clear();
            case "OTHER_TENANT" -> TenantContextHolder.setTenantId(2L);
            case "TENANT_SKIP" -> TenantContextHolder.setTenantSkip();
            case "FACTORY_SKIP" -> FactoryContextHolder.setFactorySkip();
        }
        assertThrows(CheckedException.class, () -> outboxes.evidence(id, "F1"));
    }

    private <T> T proxy(T value, DataSourceTransactionManager manager) {
        var proxy = new ProxyFactory(value);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1000001})
    void invalidAttemptCounterIsRefusedBeforeRemoteDelivery(int count) {
        var row = enqueue();
        db.update(
                "UPDATE mes_plan_completion_outbox SET attempt_count=? WHERE id=?",
                count,
                row.getId());
        assertThrows(CheckedException.class, () -> worker.deliverOne(row.getId(), "F1"));
        assertEquals(0, receiver.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {98, 99})
    void tinyFinalApsSourceCannotReceiveMoreThanItsAllocationFromAccumulatedRounding(
            int largeSources) {
        String planned = largeSources == 98 ? "110.00000000" : "99.00000001";
        String completed = largeSources == 98 ? "109.999999" : "98.99999950";
        db.update(
                "UPDATE mes_task SET notice_num=?,complete_num=? WHERE id=1",
                new BigDecimal(planned),
                new BigDecimal(completed));
        for (int i = 1; i <= largeSources; i++)
            allocation(1, 1000 + i, i, 2000 + i, "P-" + i, "1", "1");
        if (largeSources == 98) allocation(1, 1099, 99, 2099, "P-99", "11.99999999", "11.99999999");
        allocation(1, 1100, 100, 2100, "P-100", "0.00000001", "0.00000001");
        var notice =
                transaction.execute(status -> outboxes.enqueue(task, new BigDecimal(completed)));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM mes_aps_batch_allocation WHERE completed_quantity<0"
                            + " OR completed_quantity>allocated_quantity",
                        Integer.class));
        var lines = outboxes.evidence(notice.getId(), "F1").getEvent().getSources();
        assertEquals(100, lines.size());
        assertEquals(
                0,
                new BigDecimal(completed)
                        .compareTo(
                                lines.stream()
                                        .map(
                                                com.kitex.mom.mes.dto.MesPlanCompletionDTO.Line
                                                        ::getCompletedQuantity)
                                        .reduce(BigDecimal.ZERO, BigDecimal::add)));
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
