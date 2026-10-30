package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.plan.dto.PlanAutomationEventDTO;
import com.kitex.mom.plan.feign.RemotePlnAutomationEventService;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import javax.sql.DataSource;

/** 批准冲销的本地负向原件、真实持久化及提交后投递。外部PLAN为受控永久收件端口。 */
class MesCompletionReopenTransactionTest {
    private JdbcTemplate db;
    private TransactionTemplate tx;
    private DataSourceTransactionManager transactions;
    private MesPlanCompletionReopenEventService service;
    private MesPlanCompletionReopenEventWorker worker;
    private MesPlanCompletionReopenEventFailureService failures;
    private final Map<Class<?>, Object> dependencies = new HashMap<>();
    private final Map<String, PlanAutomationEventDTO.ReceiveReceipt> receiver = new HashMap<>();
    private boolean loseResponse;
    private boolean malformedReceipt;
    private MesTaskEntity before, after;
    private Long requestId;

    @BeforeEach
    void setup() throws Exception {
        scope(1L, "F1");
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:reopen-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        db = new JdbcTemplate(ds);
        transactions = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(transactions);
        dependencies.put(DataSource.class, ds);
        var config = new MybatisConfiguration();
        var global = new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(
                new com.kitex.common.data.mybatis.MybatisPlusMetaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(config, global);
        for (var type :
                List.of(
                        MesTaskMapper.class,
                        MesApsBatchAllocationMapper.class,
                        MesReportReverseRequestMapper.class,
                        MesPlanCompletionReopenOutboxMapper.class,
                        MesPlanCompletionReopenOperationMapper.class,
                        MesPlanCompletionMapper.class,
                        MesPlanCompletionOutboxMapper.class)) config.addMapper(type);
        for (var type :
                List.of(
                        MesTaskEntity.class,
                        MesApsBatchAllocationEntity.class,
                        MesReportReverseRequestEntity.class,
                        MesPlanCompletionReopenOutboxEntity.class,
                        MesPlanCompletionReopenOperationEntity.class,
                        MesPlanCompletionOutboxEntity.class)) table(config, type);
        db.execute("CREATE SCHEMA mom_mes");
        for (String table :
                List.of(
                        "mes_task",
                        "mes_aps_batch_allocation",
                        "mes_report_reverse_request",
                        "mes_plan_completion_reopen_outbox",
                        "mes_plan_completion_reopen_operation",
                        "mes_plan_completion_outbox"))
            db.execute("CREATE SYNONYM mom_mes." + table + " FOR PUBLIC." + table);
        db.execute("CREATE TABLE source_lock(id int primary key)");
        db.update("INSERT INTO source_lock VALUES(1)");
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
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
                            public boolean ignoreTable(String name) {
                                return TenantContextHolder.getTenantSkip();
                            }
                        }));
        factory.setPlugins(plugins);
        var sessions = new SqlSessionTemplate(factory.getObject());
        for (var type :
                List.of(
                        MesTaskMapper.class,
                        MesApsBatchAllocationMapper.class,
                        MesReportReverseRequestMapper.class,
                        MesPlanCompletionReopenOutboxMapper.class,
                        MesPlanCompletionReopenOperationMapper.class,
                        MesPlanCompletionOutboxMapper.class))
            dependencies.put(type, sessions.getMapper(type));
        var rawTask = sessions.getMapper(MesTaskMapper.class);
        var tasks =
                (MesTaskMapper)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {MesTaskMapper.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("acquireApsBatchTransactionLock"))
                                        return db.queryForObject(
                                                "SELECT id FROM source_lock WHERE id=1 FOR UPDATE",
                                                Integer.class);
                                    try {
                                        return method.invoke(rawTask, args);
                                    } catch (java.lang.reflect.InvocationTargetException e) {
                                        throw e.getCause();
                                    }
                                });
        dependencies.put(MesTaskMapper.class, tasks);
        var rawFacts = sessions.getMapper(MesPlanCompletionMapper.class);
        var facts =
                (MesPlanCompletionMapper)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {MesPlanCompletionMapper.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("lockEnqueue"))
                                        return db.queryForObject(
                                                "SELECT id FROM source_lock WHERE id=1 FOR UPDATE",
                                                Integer.class);
                                    try {
                                        return method.invoke(rawFacts, args);
                                    } catch (java.lang.reflect.InvocationTargetException e) {
                                        throw e.getCause();
                                    }
                                });
        dependencies.put(MesPlanCompletionMapper.class, facts);
        var json = platformJson();
        dependencies.put(ObjectMapper.class, json);
        dependencies.put(
                MesPlanCompletionOutboxService.class,
                new MesPlanCompletionOutboxService(
                        facts, sessions.getMapper(MesPlanCompletionOutboxMapper.class), ds, json));
        var access = mock(MesActualFactoryAccessService.class);
        when(access.require(anyString())).thenAnswer(call -> FactoryContextHolder.getFactoryCode());
        dependencies.put(MesActualFactoryAccessService.class, access);
        var remote = mock(RemotePlnAutomationEventService.class);
        when(remote.receive(any()))
                .thenAnswer(
                        call -> {
                            var command =
                                    (PlanAutomationEventDTO.ReceiveCommand) call.getArgument(0);
                            assertEquals(
                                    command.getFactoryCode(),
                                    FactoryContextHolder.getFactoryCode());
                            var model =
                                    cn.hutool.json.JSONUtil.parseObj(command.getPayloadJson())
                                            .getInt("sourceSchemaVersion");
                            assertEquals(model == null ? 1 : model, command.getEventVersion());
                            String key =
                                    TenantContextHolder.getTenantId()
                                            + ":"
                                            + command.getFactoryCode()
                                            + ":"
                                            + command.getEventId();
                            var receipt =
                                    receiver.computeIfAbsent(
                                            key,
                                            k ->
                                                    new PlanAutomationEventDTO.ReceiveReceipt()
                                                            .setInboxId(500L + receiver.size())
                                                            .setEventId(command.getEventId())
                                                            .setEventNo("EVENT-" + receiver.size())
                                                            .setStatus("RECEIVED")
                                                            .setReceivedTime(LocalDateTime.now()));
                            if (loseResponse) {
                                loseResponse = false;
                                throw new IllegalStateException(
                                        "ack lost after durable remote receive");
                            }
                            if (malformedReceipt)
                                return R.ok(
                                        new PlanAutomationEventDTO.ReceiveReceipt()
                                                .setInboxId(receipt.getInboxId())
                                                .setEventId(receipt.getEventId())
                                                .setEventNo(receipt.getEventNo())
                                                .setStatus("UNKNOWN_STATE"));
                            return R.ok(receipt);
                        });
        dependencies.put(RemotePlnAutomationEventService.class, remote);
        worker = proxy(construct(MesPlanCompletionReopenEventWorker.class));
        dependencies.put(MesPlanCompletionReopenEventWorker.class, worker);
        failures = proxy(construct(MesPlanCompletionReopenEventFailureService.class));
        dependencies.put(MesPlanCompletionReopenEventFailureService.class, failures);
        service = proxy(construct(MesPlanCompletionReopenEventService.class));
        seed(1L, 90L, 1L, "F1");
    }

    private void scope(Long tenant, String factory) {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        TenantContextHolder.setTenantId(tenant);
        FactoryContextHolder.setFactoryCode(factory);
    }

    private void seed(Long id, Long request, Long tenant, String factory) {
        scope(tenant, factory);
        requestId = request;
        before =
                new MesTaskEntity()
                        .setCode("TASK-" + id)
                        .setPlanNo("PLAN-1")
                        .setMatCode("MAT1")
                        .setFactoryCode(factory)
                        .setStatus("FINISH")
                        .setNoticeNum(new BigDecimal("2"))
                        .setCompleteNum(new BigDecimal("2"))
                        .setCompletionVersion(0);
        before.setId(id);
        before.setTenantId(tenant);
        after =
                new MesTaskEntity()
                        .setCode(before.getCode())
                        .setPlanNo("PLAN-1")
                        .setMatCode("MAT1")
                        .setFactoryCode(factory)
                        .setStatus("PRODUCT")
                        .setNoticeNum(new BigDecimal("2"))
                        .setCompleteNum(BigDecimal.ONE)
                        .setCompletionVersion(1);
        after.setId(id);
        after.setTenantId(tenant);
        db.update(
                "INSERT INTO"
                    + " mes_task(id,tenant_id,factory_code,code,plan_no,mat_code,status,notice_num,complete_num,completion_version)"
                    + " VALUES(?,?,?,?,'PLAN-1','MAT1','PRODUCT',2,1,1)",
                id,
                tenant,
                factory,
                after.getCode());
        db.update(
                "INSERT INTO mes_report_reverse_request(id,tenant_id,factory_code,task_code,status)"
                        + " VALUES(?,?,?,?,'APPROVED')",
                request,
                tenant,
                factory,
                after.getCode());
    }

    private MesPlanCompletionReopenOutboxEntity enqueue() {
        return tx.execute(status -> service.enqueue(before, after, requestId));
    }

    @Test
    void newNegativeEventFreezesOnlyTheCorrectedTaskSourceInsteadOfOtherTasks() {
        db.update(
                "INSERT INTO"
                    + " mes_task(id,tenant_id,factory_code,code,plan_no,mat_code,status,notice_num,complete_num,completion_version)"
                    + " VALUES(2,1,'F1','PEER','PLAN-1','MAT1','FINISH',3,3,0)");
        var row = enqueue();
        assertNotNull(row);
        var payload = cn.hutool.json.JSONUtil.parseObj(row.getPayloadJson());
        var line = payload.getJSONArray("sourceLines").getJSONObject(0);
        assertEquals(0, new BigDecimal("2").compareTo(line.getBigDecimal("allocatedQuantity")));
        assertEquals(0, BigDecimal.ONE.compareTo(line.getBigDecimal("completedQuantity")));
        assertEquals(2, payload.getInt("sourceSchemaVersion"));
    }

    @Test
    void successfulDeliveryActuallyClearsRetryTimeAndPreviousError() {
        var row = enqueue();
        db.update(
                "UPDATE mes_plan_completion_reopen_outbox SET last_error='old error' WHERE id=?",
                row.getId());
        assertTrue(worker.deliverOne(row.getId()));
        assertNull(
                db.queryForObject(
                        "SELECT next_attempt_time FROM mes_plan_completion_reopen_outbox",
                        LocalDateTime.class));
        assertNull(
                db.queryForObject(
                        "SELECT last_error FROM mes_plan_completion_reopen_outbox", String.class));
        assertEquals(
                "DELIVERED",
                db.queryForObject(
                        "SELECT status FROM mes_plan_completion_reopen_outbox", String.class));
    }

    private void allocation(long id, int seq, long issue, String number, String quantity) {
        db.update(
                "UPDATE mes_task SET"
                    + " aps_task_id=100,aps_batch_id=101,aps_batch_no='BATCH-1',aps_batch_fingerprint=?"
                    + " WHERE id=1",
                "f".repeat(64));
        db.update(
                "INSERT INTO"
                    + " mes_aps_batch_allocation(id,tenant_id,factory_code,mes_task_id,mes_task_code,aps_task_id,aps_batch_id,aps_batch_no,aps_batch_fingerprint,allocation_seq,issue_id,issue_no,issue_quantity_snapshot,allocated_quantity,completed_quantity)"
                    + " VALUES(?,1,'F1',1,'TASK-1',100,101,'BATCH-1',?,?,?,?,?,?,0)",
                id,
                "f".repeat(64),
                seq,
                issue,
                number,
                new BigDecimal(quantity),
                new BigDecimal(quantity));
    }

    @Test
    void apsReversalUsesBoundedCumulativeRoundingForOneHundredSources() {
        before.setNoticeNum(new BigDecimal("110")).setCompleteNum(new BigDecimal("110"));
        after.setNoticeNum(new BigDecimal("110")).setCompleteNum(new BigDecimal("109.999999"));
        db.update("UPDATE mes_task SET notice_num=110,complete_num=109.999999 WHERE id=1");
        for (int i = 1; i <= 98; i++) allocation(1000 + i, i, 2000 + i, "PLAN-" + i, "1");
        allocation(1099, 99, 2099, "PLAN-99", "11.99999999");
        allocation(1100, 100, 2100, "PLAN-100", "0.00000001");
        var row = enqueue();
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM mes_aps_batch_allocation WHERE completed_quantity<0"
                                + " OR completed_quantity>allocated_quantity",
                        Integer.class));
        var evidence = service.evidence(row.getId(), "F1");
        assertTrue(evidence.getSourceValid());
        assertEquals(100, evidence.getSourceLines().size());
        assertEquals(
                0,
                new BigDecimal("109.999999")
                        .compareTo(
                                evidence.getSourceLines().stream()
                                        .map(
                                                com.kitex.mom.mes.dto
                                                                .MesPlanCompletionReopenEvidence
                                                                .SourceLine
                                                        ::getCompletedQuantity)
                                        .reduce(BigDecimal.ZERO, BigDecimal::add)));
    }

    @Test
    void sameApprovedCorrectionIsReplayableWithoutNewAllocationOrOperation() {
        var first = enqueue();
        var second = enqueue();
        assertEquals(first.getId(), second.getId());
        assertEquals(first.getPayloadJson(), second.getPayloadJson());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM mes_plan_completion_reopen_operation",
                        Integer.class));
    }

    @Test
    void aLegacyNullCompletionVersionIsStillTheOriginalZeroGeneration() {
        before.setCompletionVersion(null);
        var row = assertDoesNotThrow(this::enqueue);
        assertEquals(0, row.getCompletionVersionBefore());
        assertEquals(1, row.getCompletionVersionAfter());
    }

    @Test
    void changedQuantityCannotReplaceAnAlreadyFrozenCorrection() {
        enqueue();
        after.setCompleteNum(BigDecimal.ZERO);
        db.update("UPDATE mes_task SET complete_num=0 WHERE id=1");
        assertThrows(CheckedException.class, this::enqueue);
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM mes_plan_completion_reopen_outbox", Integer.class));
    }

    @Test
    void callerQuantityMustMatchTheActuallySavedCorrection() {
        after.setCompleteNum(BigDecimal.ZERO);
        assertThrows(CheckedException.class, this::enqueue);
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM mes_plan_completion_reopen_outbox", Integer.class));
    }

    @Test
    void anApprovedRequestForADifferentTaskCannotProveThisCorrection() {
        var row = enqueue();
        db.update("UPDATE mes_report_reverse_request SET task_code='OTHER' WHERE id=90");
        assertFalse(service.evidence(row.getId(), "F1").getSourceValid());
    }

    @Test
    void aSubmittedRequestOnlyBecomesEvidenceAfterItsApprovalCommits() {
        db.update("UPDATE mes_report_reverse_request SET status='SUBMITTED' WHERE id=90");
        var row = enqueue();
        assertFalse(service.evidence(row.getId(), "F1").getSourceValid());
        db.update("UPDATE mes_report_reverse_request SET status='APPROVED' WHERE id=90");
        assertTrue(service.evidence(row.getId(), "F1").getSourceValid());
    }

    @Test
    void reworkCompletionCannotReopenTheOriginalProductionPlan() {
        db.update(
                "UPDATE mes_task SET"
                    + " resource_type='REWORK',rework_request_id='R1',source_task_code='ORIGINAL'"
                    + " WHERE id=1");
        assertNull(enqueue());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM mes_plan_completion_reopen_outbox", Integer.class));
    }

    @Test
    void negativeEvidenceSurvivesLaterCompletionAndLogicalDeletionWithoutChangingItsOriginal() {
        var row = enqueue();
        db.update(
                "UPDATE mes_task SET"
                        + " status='FINISH',completion_version=2,complete_num=2,del_flag='1' WHERE"
                        + " id=1");
        var evidence = service.evidence(row.getId(), "F1");
        assertTrue(evidence.getSourceValid());
        assertEquals(1, evidence.getCompletionVersionAfter());
        assertEquals(0, BigDecimal.ONE.compareTo(evidence.getCompleteQuantityAfter()));
        assertEquals(
                row.getPayloadJson(),
                db.queryForObject(
                        "SELECT payload_json FROM mes_plan_completion_reopen_outbox",
                        String.class));
    }

    @Test
    void operationFailureRollsBackTheCorrectionAndItsOutboxAsOneTransaction() {
        db.update(
                "UPDATE mes_task SET"
                    + " status='FINISH',complete_num=2,completion_version=0,finish_time=CURRENT_TIMESTAMP"
                    + " WHERE id=1");
        db.execute(
                "ALTER TABLE mes_plan_completion_reopen_operation ADD CONSTRAINT reject_operation"
                        + " CHECK(action<>'ENQUEUE')");
        assertThrows(
                RuntimeException.class,
                () ->
                        tx.executeWithoutResult(
                                status -> {
                                    db.update(
                                            "UPDATE mes_task SET"
                                                + " status='PRODUCT',complete_num=1,completion_version=1,finish_time=NULL"
                                                + " WHERE id=1");
                                    service.enqueue(before, after, requestId);
                                }));
        assertEquals("FINISH", db.queryForObject("SELECT status FROM mes_task", String.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM mes_plan_completion_reopen_outbox", Integer.class));
    }

    @Test
    void lostReceiveResponseRetriesTheExactEventAndDoesNotCreateAnotherInbox() {
        var row = enqueue();
        loseResponse = true;
        assertThrows(IllegalStateException.class, () -> worker.deliverOne(row.getId()));
        assertEquals(
                "PENDING",
                db.queryForObject(
                        "SELECT status FROM mes_plan_completion_reopen_outbox", String.class));
        assertEquals(1, receiver.size());
        failures.mark(row.getId(), new IllegalStateException("connection lost"));
        db.update(
                "UPDATE mes_plan_completion_reopen_outbox SET next_attempt_time=TIMESTAMP"
                        + " '2026-01-01 00:00:00'");
        assertTrue(worker.deliverOne(row.getId()));
        assertEquals(1, receiver.size());
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT attempt_count FROM mes_plan_completion_reopen_outbox",
                        Integer.class));
    }

    @Test
    void localCommitFailureAfterRemoteReceiveStillUsesTheOriginalEventOnRetry() {
        var row = enqueue();
        db.execute(
                "ALTER TABLE mes_plan_completion_reopen_outbox ADD CONSTRAINT reject_delivered"
                        + " CHECK(status<>'DELIVERED')");
        assertThrows(RuntimeException.class, () -> worker.deliverOne(row.getId()));
        assertEquals(
                "PENDING",
                db.queryForObject(
                        "SELECT status FROM mes_plan_completion_reopen_outbox", String.class));
        assertEquals(1, receiver.size());
        db.execute(
                "ALTER TABLE mes_plan_completion_reopen_outbox DROP CONSTRAINT reject_delivered");
        assertTrue(worker.deliverOne(row.getId()));
        assertEquals(1, receiver.size());
    }

    @Test
    void fiveFailedAttemptsStopAutomaticDeliveryAndClearTheDueTime() {
        var row = enqueue();
        for (int i = 0; i < 5; i++)
            failures.mark(row.getId(), new IllegalStateException("offline"));
        assertEquals(
                "DEAD_LETTER",
                db.queryForObject(
                        "SELECT status FROM mes_plan_completion_reopen_outbox", String.class));
        assertNull(
                db.queryForObject(
                        "SELECT next_attempt_time FROM mes_plan_completion_reopen_outbox",
                        LocalDateTime.class));
        assertFalse(worker.deliverOne(row.getId()));
    }

    private void dead(Long id) {
        db.update(
                "UPDATE mes_plan_completion_reopen_outbox SET"
                    + " status='DEAD_LETTER',attempt_count=5,next_attempt_time=NULL,last_error='old"
                    + " failure' WHERE id=?",
                id);
    }

    @Test
    void anOperatorRetryActuallyClearsThePreviousFailure() {
        var row = enqueue();
        dead(row.getId());
        service.retry(row.getId(), "RETRY_ORIGINAL_A_123");
        assertNull(
                db.queryForObject(
                        "SELECT last_error FROM mes_plan_completion_reopen_outbox", String.class));
        assertEquals(
                "RETRY_WAIT",
                db.queryForObject(
                        "SELECT status FROM mes_plan_completion_reopen_outbox", String.class));
    }

    @Test
    void anOlderOperatorRequestCannotResetAttemptsAgainAfterAnotherManualRetry() {
        var row = enqueue();
        dead(row.getId());
        service.retry(row.getId(), "RETRY_ORIGINAL_A_123");
        dead(row.getId());
        service.retry(row.getId(), "RETRY_ORIGINAL_B_123");
        dead(row.getId());
        service.retry(row.getId(), "RETRY_ORIGINAL_A_123");
        assertEquals(
                "DEAD_LETTER",
                db.queryForObject(
                        "SELECT status FROM mes_plan_completion_reopen_outbox", String.class));
        assertEquals(
                5,
                db.queryForObject(
                        "SELECT attempt_count FROM mes_plan_completion_reopen_outbox",
                        Integer.class));
    }

    @Test
    void backgroundDeliveryEntersEachRealOrganizationAndRestoresTheCallerScope() {
        enqueue();
        seed(2L, 91L, 2L, "F2");
        enqueue();
        scope(99L, "PREVIOUS");
        TenantContextHolder.setTenantSkip();
        FactoryContextHolder.setFactorySkip();
        assertEquals(2, service.dispatchDue(100));
        assertEquals(2, receiver.size());
        assertEquals(99L, TenantContextHolder.getTenantId());
        assertEquals("PREVIOUS", FactoryContextHolder.getFactoryCode());
        assertTrue(TenantContextHolder.getTenantSkip());
        assertTrue(FactoryContextHolder.getFactorySkip());
    }

    @Test
    void interactiveDetailDoesNotAcceptAnOrganizationBypass() {
        var row = enqueue();
        TenantContextHolder.setTenantSkip();
        assertThrows(CheckedException.class, () -> service.detail(row.getId()));
    }

    @Test
    void aLegacyAggregatePayloadIsDeliveredUnchangedWithItsOriginalVersion() {
        var row = enqueue();
        var payload =
                service.evidence(row.getId(), "F1")
                        .setSourceSchemaVersion(null)
                        .setPayloadFingerprint(null)
                        .setSourceValid(null);
        payload.setSourceLines(
                List.of(
                        new com.kitex.mom.mes.dto.MesPlanCompletionReopenEvidence.SourceLine()
                                .setIssueNo("PLAN-1")
                                .setAllocatedQuantity(new BigDecimal("5"))
                                .setCompletedQuantity(new BigDecimal("4"))));
        String original = cn.hutool.json.JSONUtil.toJsonStr(payload);
        db.update(
                "UPDATE mes_plan_completion_reopen_outbox SET payload_json=?,payload_fingerprint=?"
                        + " WHERE id=?",
                original,
                MesPlanCompletionReopenEventService.hash(original),
                row.getId());
        assertTrue(service.evidence(row.getId(), "F1").getSourceValid());
        assertTrue(worker.deliverOne(row.getId()));
        assertEquals(
                original,
                db.queryForObject(
                        "SELECT payload_json FROM mes_plan_completion_reopen_outbox",
                        String.class));
    }

    @Test
    void anUnknownFutureModelCannotBeSentAsVersionOne() {
        var row = enqueue();
        var payload =
                cn.hutool.json.JSONUtil.parseObj(row.getPayloadJson())
                        .set("sourceSchemaVersion", 3);
        String raw = payload.toString();
        db.update(
                "UPDATE mes_plan_completion_reopen_outbox SET payload_json=?,payload_fingerprint=?"
                        + " WHERE id=?",
                raw,
                MesPlanCompletionReopenEventService.hash(raw),
                row.getId());
        assertThrows(CheckedException.class, () -> worker.deliverOne(row.getId()));
        assertEquals(0, receiver.size());
    }

    @Test
    void anAlteredPayloadHeaderCannotBecomeApprovedEvidenceByChangingItsHash() {
        var row = enqueue();
        String raw =
                cn.hutool.json.JSONUtil.parseObj(row.getPayloadJson())
                        .set("productCode", "OTHER")
                        .toString();
        db.update(
                "UPDATE mes_plan_completion_reopen_outbox SET payload_json=?,payload_fingerprint=?"
                        + " WHERE id=?",
                raw,
                MesPlanCompletionReopenEventService.hash(raw),
                row.getId());
        assertThrows(CheckedException.class, () -> service.evidence(row.getId(), "F1"));
    }

    @Test
    void manualRetryAndItsPermanentOperationMustCommitTogether() {
        var row = enqueue();
        dead(row.getId());
        db.execute(
                "ALTER TABLE mes_plan_completion_reopen_operation ADD CONSTRAINT reject_retry"
                        + " CHECK(action<>'MANUAL_RETRY')");
        assertThrows(
                RuntimeException.class, () -> service.retry(row.getId(), "RETRY_ROLLBACK_123"));
        assertEquals(
                "DEAD_LETTER",
                db.queryForObject(
                        "SELECT status FROM mes_plan_completion_reopen_outbox", String.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM mes_plan_completion_reopen_operation WHERE"
                                + " action='MANUAL_RETRY'",
                        Integer.class));
    }

    @Test
    void aTenantWideRetryRequestCannotBeReusedForAnotherFactorysEvent() {
        var row = enqueue();
        dead(row.getId());
        service.retry(row.getId(), "RETRY_FACTORY_123");
        seed(2L, 91L, 1L, "F2");
        var other = enqueue();
        dead(other.getId());
        assertThrows(
                CheckedException.class, () -> service.retry(other.getId(), "RETRY_FACTORY_123"));
        assertEquals(
                "DEAD_LETTER",
                db.queryForObject(
                        "SELECT status FROM mes_plan_completion_reopen_outbox WHERE id=?",
                        String.class,
                        other.getId()));
    }

    @Test
    void concurrentEnqueueOfTheSameApprovedCorrectionKeepsOnePermanentEvent() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var results = new ArrayList<java.util.concurrent.Future<Long>>();
            for (int i = 0; i < 2; i++)
                results.add(
                        pool.submit(
                                () -> {
                                    scope(1L, "F1");
                                    try {
                                        ready.countDown();
                                        assertTrue(
                                                start.await(
                                                        5, java.util.concurrent.TimeUnit.SECONDS));
                                        return enqueue().getId();
                                    } finally {
                                        TenantContextHolder.clear();
                                        FactoryContextHolder.clear();
                                    }
                                }));
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            assertEquals(
                    results.get(0).get(10, java.util.concurrent.TimeUnit.SECONDS),
                    results.get(1).get(10, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM mes_plan_completion_reopen_outbox", Integer.class));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM mes_plan_completion_reopen_operation WHERE"
                                + " action='ENQUEUE'",
                        Integer.class));
    }

    @Test
    void malformedInboxReceiptDoesNotMarkTheEventDelivered() {
        var row = enqueue();
        malformedReceipt = true;
        assertThrows(CheckedException.class, () -> worker.deliverOne(row.getId()));
        assertEquals(
                "PENDING",
                db.queryForObject(
                        "SELECT status FROM mes_plan_completion_reopen_outbox", String.class));
        assertNull(
                db.queryForObject(
                        "SELECT plan_inbox_id FROM mes_plan_completion_reopen_outbox", Long.class));
        malformedReceipt = false;
        assertTrue(worker.deliverOne(row.getId()));
        assertEquals(1, receiver.size());
    }

    private ObjectMapper platformJson() {
        var builder = new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder();
        new com.kitex.common.core.config.JacksonConfiguration().customizer().customize(builder);
        return builder.build();
    }

    @Test
    void platformJsonCodecCanPersistItsOwnReopenTime() {
        var row = assertDoesNotThrow(this::enqueue);
        assertTrue(service.evidence(row.getId(), "F1").getSourceValid());
    }

    private <T> T construct(Class<T> type) throws Exception {
        var c = type.getDeclaredConstructors()[0];
        return type.cast(
                c.newInstance(
                        Arrays.stream(c.getParameterTypes())
                                .map(t -> dependencies.computeIfAbsent(t, k -> mock(t)))
                                .toArray()));
    }

    private <T> T proxy(T target) {
        var p = new ProxyFactory(target);
        p.setProxyTargetClass(true);
        p.addAdvice(
                new TransactionInterceptor(
                        transactions, new AnnotationTransactionAttributeSource()));
        return (T) p.getProxy();
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
