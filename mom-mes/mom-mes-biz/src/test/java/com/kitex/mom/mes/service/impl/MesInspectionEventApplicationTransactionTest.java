package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.*;
import com.kitex.mom.qms.api.feign.RemoteProcedureService;
import com.kitex.mom.qms.dto.inspection.*;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** 真实历史收件/创建原件/门控与报工、库存/证明同库；QMS和配置端口受控。 */
public class MesInspectionEventApplicationTransactionTest {
    static final String KEY = "ordinary-event-application-test-key-at-least32";
    static final ObjectMapper JSON = new ObjectMapper();
    MesQualityQuantityTransactionTest stock;
    JdbcTemplate db;
    SqlSessionTemplate session;
    DataSourceTransactionManager tx;
    MesInspectionEventInboxService inbox;
    MesInspectionEventProjection projection;
    MesInspectionEventApplicationWorker worker;
    MesQualityGateService gate;
    MesQualityQuantityService quantities;
    MesInspectionEventStateMapper states;
    MesInspectionRuleEntity rule;
    MesQualityGateSourceEvidence sources;
    Map<Class<?>, Object> deps;
    Map<Long, ObjectNode> bodies = new HashMap<>();
    WriteFault fault = new WriteFault();
    MesProductionTaskLock productionLock;
    MesProductionTaskMutex mutex;
    MesInspectionRevisionHold holds;
    MesSourceQuantityPlanOutbox planOutbox;
    int remoteCalls;
    java.util.concurrent.CountDownLatch remoteEntered, remoteRelease;
    MesChangePointHandleMapper changes;

    @BeforeEach
    void setup() throws Exception {
        stock = new MesQualityQuantityTransactionTest();
        stock.setup();
        db = (JdbcTemplate) ReflectionTestUtils.getField(stock, "db");
        session = (SqlSessionTemplate) ReflectionTestUtils.getField(stock, "sessions");
        tx = (DataSourceTransactionManager) ReflectionTestUtils.getField(stock, "transactions");
        deps = (Map<Class<?>, Object>) ReflectionTestUtils.getField(stock, "dependencies");
        quantities = (MesQualityQuantityService) ReflectionTestUtils.getField(stock, "quality");
        db.execute("CREATE SCHEMA mom_mes");
        db.execute("CREATE SCHEMA \"mom_mes\"");
        for (String table :
                List.of(
                        "mes_task",
                        "mes_produce_task",
                        "mes_task_arrange",
                        "mes_task_report",
                        "mes_task_report_record",
                        "mes_warehouse_live_stock",
                        "mes_quality_stock_proof"))
            db.execute("CREATE SYNONYM mom_mes." + table + " FOR public." + table);
        var c = session.getConfiguration();
        for (var type :
                List.of(
                        MesInspectionGenRecordEntity.class,
                        MesInspectionEventInboxEntity.class,
                        MesInspectionEventStateEntity.class,
                        MesInspectionEventApplicationEntity.class,
                        MesQmsInspectionCommandEntity.class,
                        MesSourceQuantityPlanOutboxEntity.class))
            ReflectionTestUtils.invokeMethod(stock, "table", c, type);
        for (var type :
                List.of(
                        MesInspectionGenRecordMapper.class,
                        MesInspectionEventInboxMapper.class,
                        MesInspectionEventStateMapper.class,
                        MesQmsInspectionCommandMapper.class,
                        MesQualityGateSourceMapper.class,
                        MesIdentifierBindingGroupMapper.class,
                        MesSourceQuantityPlanOutboxMapper.class, MesReworkSourcePlanOutboxMapper.class)) c.addMapper(type);
        c.addInterceptor(fault);
        db.execute(
                "CREATE ALIAS IF NOT EXISTS HASHTEXTEXTENDED FOR"
                    + " 'com.kitex.mom.mes.service.impl.MesInspectionEventApplicationTransactionTest.lockHash'");
        db.execute(
                "CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK FOR"
                    + " 'com.kitex.mom.mes.service.impl.MesInspectionEventApplicationTransactionTest.lockTable'");
        db.execute(
                "CREATE ALIAS mom_mes.mom_source_quantity_plan_outbox_ready_v1 AS 'Integer"
                        + " ready(){return 1;}'");
        db.execute("CREATE TABLE mom_mes.mes_rework_source_plan_outbox AS SELECT * FROM mom_mes.mes_source_quantity_plan_outbox WHERE 1=0");
        db.execute("CREATE ALIAS mom_mes.mom_rework_source_plan_ready_v1 AS 'Integer ready(){return 1;}'");
        MesInspectionRevisionAdmissionTestSupport.install(session, db);
        mutex =
                proxy(
                        new MesProductionTaskMutex(
                                session.getMapper(MesIdentifierBindingGroupMapper.class)));
        holds =
                new MesInspectionRevisionHold(
                        session.getMapper(MesInspectionRevisionAdmissionMapper.class));
        productionLock = proxy(new MesProductionTaskLock(mutex, holds));
        ReflectionTestUtils.setField(quantities, "productionTaskLock", productionLock);
        planOutbox =
                proxy(
                        new MesSourceQuantityPlanOutbox(
                                session.getMapper(MesSourceQuantityPlanOutboxMapper.class),
                                session.getMapper(MesReworkSourcePlanOutboxMapper.class),
                                mock(com.kitex.mom.plan.feign.RemotePlnIssueService.class)));

        db.execute(
                "CREATE SYNONYM mom_mes.mes_qms_inspection_command FOR"
                        + " public.mes_qms_inspection_command");
        db.execute(
                "CREATE SYNONYM \"mom_mes\".mes_qms_inspection_command FOR"
                        + " public.mes_qms_inspection_command");
        db.execute(
                "CREATE ALIAS \"mom_mes\".mom_qms_inspection_command_ready_v1 AS 'Boolean"
                        + " ready(){return true;}'");
        db.execute(
                "CREATE ALIAS mom_mes.mom_inspection_event_inbox_ready_v1 AS 'Integer"
                        + " ready(){return 1;}'");
        db.execute("CREATE TABLE application_installation(version int)");
        db.update("INSERT INTO application_installation VALUES(1)");
        db.execute(
                "CREATE ALIAS mom_mes.mom_inspection_event_application_ready_v1 FOR"
                    + " 'com.kitex.mom.mes.service.impl.MesInspectionEventApplicationTransactionTest.ready'");
        states = session.getMapper(MesInspectionEventStateMapper.class);
        sources =
                new MesQualityGateSourceEvidence(
                        session.getMapper(MesQmsInspectionCommandMapper.class),
                        session.getMapper(MesQualityGateSourceMapper.class));
        var receive =
                new MesInspectionEventInboxService(
                        session.getMapper(MesInspectionEventInboxMapper.class), sources, states);
        ReflectionTestUtils.setField(receive, "deliverySecret", KEY);
        inbox = proxy(receive);
        projection = new MesInspectionEventProjection(states, inbox, sources, holds);
        var records = new MesInspectionGenRecordServiceImpl();
        ReflectionTestUtils.setField(
                records, "baseMapper", session.getMapper(MesInspectionGenRecordMapper.class));
        rule = new MesInspectionRuleEntity().setGateMode("BLOCK").setInspectionTypes("FIRST");
        rule.setStatus("1");
        rule.setCode("R1");
        rule.setTenantId(1L);
        rule.setFactoryCode("F1");
        var rules = mock(MesInspectionRuleService.class);
        when(rules.getRuleByMatCode(any())).thenReturn(rule);
        var remote = mock(RemoteProcedureService.class);
        when(remote.getTaskByIds(anyList()))
                .thenAnswer(
                        call -> {
                            remoteCalls++;
                            List<Long> ids = call.getArgument(0);
                            var list = new ArrayList<QmsInspectionProcedureTaskEntity>();
                            for (Long id : ids) {
                                var n = bodies.get(id);
                                if (n != null) {
                                    var q = source(n);
                                    q.setResult("QUALIFIED")
                                            .setStatus("FINISHED")
                                            .setUsableNum(5)
                                            .setHandleNum(5);
                                    list.add(q);
                                }
                            }
                            if (remoteEntered != null) {
                                remoteEntered.countDown();
                                assertTrue(
                                        remoteRelease.await(
                                                5, java.util.concurrent.TimeUnit.SECONDS));
                            }
                            return R.ok(list);
                        });
        changes = mock(MesChangePointHandleMapper.class);
        var ctor = MesQualityGateServiceImpl.class.getDeclaredConstructors()[0];
        Object[] args =
                Arrays.stream(ctor.getParameterTypes())
                        .map(
                                t -> {
                                    if (t == MesQualityGateSourceEvidence.class) return sources;
                                    if (t == MesChangePointHandleMapper.class) return changes;
                                    if (t == MesInspectionEventProjection.class) return projection;
                                    if (t == MesInspectionGenRecordService.class) return records;
                                    if (t == MesInspectionRuleService.class) return rules;
                                    if (t == RemoteProcedureService.class) return remote;
                                    return mock(t);
                                })
                        .toArray();
        gate = proxy((MesQualityGateService) ctor.newInstance(args));
        ReflectionTestUtils.setField(quantities, "eventProjection", projection);
        ReflectionTestUtils.setField(quantities, "qualityGateService", gate);
        // 构造期保留旧夹具端口，新增真实依赖在同一实际服务实例替换，未模拟数量内核。
        worker =
                proxy(
                        new MesInspectionEventApplicationWorker(
                                states, inbox, projection, sources, quantities, productionLock));
        db.update("UPDATE mes_produce_task SET mat_code='MAT1',is_inspection='1' WHERE id=21");
        seed(10L, 700L, "FIRST");
    }

    @AfterEach
    void clear() {
        stock.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    public static Integer ready(java.sql.Connection c) throws Exception {
        try (var s = c.createStatement();
                var r = s.executeQuery("SELECT version FROM application_installation")) {
            return r.next() ? r.getInt(1) : null;
        }
    }

    void seed(long record, long qms, String phase) throws Exception {
        var command =
                new InspectionProcedureCreateParam()
                        .setTaskCode("P1")
                        .setSourceTaskCode("P1")
                        .setSourceRequestId("MES_QMS_V2:P1:" + phase + ":" + record)
                        .setLabelType(phase)
                        .setMatCode("MAT1")
                        .setProcessCode("R1")
                        .setStandardId(9L)
                        .setBatchNo("B1")
                        .setNum(5)
                        .setInspectionNum(1)
                        .setOrderNo("T1")
                        .setOrderId("1");
        String raw = InspectionProcedureSourceContract.snapshot(command),
                fp = InspectionProcedureSourceContract.fingerprint(command);
        db.update(
                "INSERT INTO"
                    + " mom_mes.mes_qms_inspection_command(id,tenant_id,factory_code,request_id,source_task_code,command_version,command_snapshot,command_fingerprint,status,qms_task_id)"
                    + " VALUES(?,1,'F1',?,'P1',?,?,?,'QMS_CONFIRMED',?)",
                record,
                command.getSourceRequestId(),
                InspectionProcedureSourceContract.VERSION,
                raw,
                fp,
                qms);
        db.update(
                "INSERT INTO"
                    + " mom_mes.mes_inspection_gen_record(id,tenant_id,factory_code,task_code,produce_task_code,inspection_id,source_request_id,inspection_type,gen_flag,gate_status,quality_event_managed,blocked_count)"
                    + " VALUES(?,1,'F1','T1','P1',?,?,?,'1','PENDING',FALSE,0)",
                record,
                qms,
                command.getSourceRequestId(),
                phase);
        ObjectNode j = JSON.createObjectNode();
        j.put("schemaVersion", 1);
        j.put("sourceSystem", "QMS");
        j.put("eventId", Long.toString(9000 + qms));
        j.put("tenantId", "1");
        j.put("factoryCode", "F1");
        j.put("inspectionType", "PROCEDURE");
        j.put("taskId", Long.toString(qms));
        j.put("historyId", Long.toString(8000 + qms));
        j.put("eventType", "CREATE");
        j.put("sequenceNo", 1);
        j.putNull("previousEventId");
        j.putNull("previousFingerprint");
        j.put("occurredAt", "2026-10-26T00:00:00Z");
        j.put("quantity", 5);
        j.putNull("qualified");
        j.putNull("cancelReason");
        j.put("recordedBy", "operator");
        j.put("firstPassEligible", false);
        j.putArray("dataGaps").add("未冻结单位及首复检关系");
        ObjectNode source = j.putObject("source");
        source.put("sourceTaskCode", "P1");
        source.put("sourceRequestId", command.getSourceRequestId());
        source.put("sourceRequestFingerprint", fp);
        source.put("matCode", "MAT1");
        source.put("processCode", "R1");
        source.put("labelType", phase);
        source.put("standardId", "9");
        source.put("batchNo", "B1");
        source.put("num", 5);
        source.put("inspectionNum", 1);
        source.put("status", "WAIT");
        source.putNull("result");
        source.putNull("usableNum");
        source.putNull("handleNum");
        source.putNull("handleResult");
        source.put("orderNo", "T1");
        source.put("orderId", "1");
        source.putNull("matBarcode");
        source.putNull("deptCode");
        source.putNull("latestInspectionTime");
        source.putNull("latestAppendTime");
        bodies.put(qms, j);
    }

    @Test
    void receiptLatchesFormalAuthorityBeforeAnyApplication() throws Exception {
        receive(first());
        assertEquals(
                true,
                db.queryForObject(
                        "SELECT quality_event_managed FROM mom_mes.mes_inspection_gen_record WHERE"
                                + " id=10",
                        Boolean.class));
        assertEquals("ACTIVE", state());
        assertEquals(0L, sequence());
        assertEquals(false, gate.sync("P1").getPassed());
        assertEquals(0, remoteCalls);
        quantity("quality_released_num", 0);
    }

    @Test
    void createThenQualifiedResultApplyTheExactOriginalAndRealStockOnce() throws Exception {
        receive(first());
        assertTrue(worker.consume(10L));
        assertEquals(1L, sequence());
        quantity("quality_pending_num", 5);
        var result = result(first(), "QUALIFIED", 5, 5);
        receive(result);
        assertTrue(worker.consume(10L));
        assertFalse(worker.consume(10L));
        assertEquals(2L, sequence());
        assertEquals(2, countApplications());
        quantity("quality_released_num", 5);
        quantity("quality_pending_num", 0);
        assertEquals(0, remoteCalls);
        assertEquals(
                "RELEASED",
                db.queryForObject(
                        "SELECT inspection_status FROM mes_warehouse_live_stock", String.class));
        assertEquals(
                1,
                db.queryForObject("SELECT COUNT(*) FROM mes_quality_stock_proof", Integer.class));
        assertTrue(
                db.queryForObject(
                                "SELECT quality_evidence_snapshot FROM mes_task_report WHERE"
                                        + " id=100",
                                String.class)
                        .contains("qualityEventId"));
    }

    @Test
    void missingFormalLastInspectionNeverBorrowsTheCurrentRemotePassedTask() throws Exception {
        rule.setInspectionTypes("FIRST,LAST");
        seed(20, 800, "LAST");
        receive(first());
        assertTrue(worker.consume(10L));
        receive(result(first(), "QUALIFIED", 5, 5));
        assertTrue(worker.consume(10L));
        quantity("quality_pending_num", 5);
        assertEquals(0, remoteCalls);
        var last = bodies.get(800L);
        receive(last);
        assertTrue(worker.consume(20L));
        receive(result(last, "QUALIFIED", 5, 5));
        assertTrue(worker.consume(20L));
        quantity("quality_released_num", 5);
    }

    @Test
    void nonconformingSampleDoesNotRejectTheWholeUnprocessedBatch() throws Exception {
        receive(first());
        assertTrue(worker.consume(10L));
        receive(result(first(), "UN_QUALIFIED", 0, 0));
        assertTrue(worker.consume(10L));
        quantity("quality_pending_num", 5);
        quantity("quality_rejected_num", 0);
        assertEquals(
                "FAILED",
                db.queryForObject(
                        "SELECT gate_status FROM mom_mes.mes_inspection_gen_record WHERE id=10",
                        String.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"RECEIPT", "CURSOR", "CACHE", "STOCK", "MIRROR"})
    void anyFinalSaveFailureRollsBackQuantitiesProofAndApplication(String fail) throws Exception {
        receive(first());
        assertTrue(worker.consume(10L));
        receive(result(first(), "QUALIFIED", 5, 5));
        fault.kind = fail;
        assertThrows(RuntimeException.class, () -> worker.consume(10L));
        quantity("quality_released_num", 0);
        quantity("quality_pending_num", 5);
        assertEquals(1L, sequence());
        assertEquals(1, countApplications());
        assertEquals(
                0,
                db.queryForObject("SELECT COUNT(*) FROM mes_quality_stock_proof", Integer.class));
        assertEquals(
                "PENDING",
                db.queryForObject(
                        "SELECT inspection_status FROM mes_warehouse_live_stock", String.class));
        fault.kind = "";
        assertTrue(worker.consume(10L));
        quantity("quality_released_num", 5);
    }

    @Test
    void aLaterContradictingResultBlocksTheStreamWithoutReversingConsumedOutput() throws Exception {
        receive(first());
        assertTrue(worker.consume(10L));
        var pass = result(first(), "QUALIFIED", 5, 5);
        receive(pass);
        assertTrue(worker.consume(10L));
        var changed = result(pass, "UN_QUALIFIED", 0, 0);
        receive(changed);
        assertFalse(worker.consume(10L));
        assertEquals("CONFLICT", state());
        assertEquals(2L, sequence());
        quantity("quality_released_num", 5);
        assertFalse(gate.sync("P1").getPassed());
        assertEquals(0, remoteCalls);
    }

    @Test
    void anExistingLegacyVerdictCannotBeErasedAndSilentlyAdopted() throws Exception {
        db.update(
                "UPDATE mom_mes.mes_inspection_gen_record SET"
                        + " inspection_result='QUALIFIED',gate_status='PASSED' WHERE id=10");
        receive(first());
        assertEquals("CONFLICT", state());
        assertFalse(worker.consume(10L));
        gate.sync("P1");
        assertFalse(worker.consume(10L));
        assertEquals(0, countApplications());
        assertEquals(0, remoteCalls);
    }

    @Test
    void aTamperedOriginalBlocksInsteadOfUsingTheCurrentRemoteValue() throws Exception {
        receive(first());
        db.update("UPDATE mom_mes.mes_inspection_event_inbox SET payload='{}'");
        assertFalse(worker.consume(10L));
        assertEquals("CONFLICT", state());
        assertEquals(0, countApplications());
        quantity("quality_pending_num", 5);
        assertEquals(0, remoteCalls);
    }

    @Test
    void noMigrationCanNeverSilentlyFallBackToMutableQuality() throws Exception {
        db.update("UPDATE application_installation SET version=0");
        assertThrows(RuntimeException.class, () -> receive(first()));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_mes.mes_inspection_event_inbox", Integer.class));
    }

    @Test
    void anUnmanagedProcessKeepsItsVerifiedLegacyPath() {
        var decision = gate.sync("P1");
        assertTrue(decision.getPassed());
        assertEquals(1, remoteCalls);
        quantities.reconcile("P1", decision);
        quantity("quality_released_num", 5);
        assertEquals(0, countApplications());
    }

    @Test
    void aLateLegacyReadCannotOverwriteTheFirstReceiptLatch() throws Exception {
        remoteEntered = new java.util.concurrent.CountDownLatch(1);
        remoteRelease = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        var future =
                pool.submit(
                        () -> {
                            ReflectionTestUtils.invokeMethod(stock, "context");
                            try {
                                gate.sync("P1");
                                return true;
                            } catch (RuntimeException expected) {
                                return false;
                            } finally {
                                TenantContextHolder.clear();
                                FactoryContextHolder.clear();
                                org.springframework.security.core.context.SecurityContextHolder
                                        .clearContext();
                            }
                        });
        try {
            assertTrue(remoteEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            receive(first());
            remoteRelease.countDown();
            assertFalse(future.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(
                    "PENDING",
                    db.queryForObject(
                            "SELECT gate_status FROM mom_mes.mes_inspection_gen_record WHERE id=10",
                            String.class));
            assertEquals(
                    true,
                    db.queryForObject(
                            "SELECT quality_event_managed FROM mom_mes.mes_inspection_gen_record"
                                    + " WHERE id=10",
                            Boolean.class));
        } finally {
            remoteRelease.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void anotherPhasesFirstReceiptAlsoFencesAnOldRemoteRead() throws Exception {
        rule.setInspectionTypes("LAST");
        seed(20, 800, "LAST");
        remoteEntered = new java.util.concurrent.CountDownLatch(1);
        remoteRelease = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        var future =
                pool.submit(
                        () -> {
                            ReflectionTestUtils.invokeMethod(stock, "context");
                            try {
                                gate.sync("P1");
                                return true;
                            } catch (RuntimeException expected) {
                                return false;
                            } finally {
                                TenantContextHolder.clear();
                                FactoryContextHolder.clear();
                                org.springframework.security.core.context.SecurityContextHolder
                                        .clearContext();
                            }
                        });
        try {
            assertTrue(remoteEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            receive(first());
            remoteRelease.countDown();
            assertFalse(future.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(
                    "PENDING",
                    db.queryForObject(
                            "SELECT gate_status FROM mom_mes.mes_inspection_gen_record WHERE id=20",
                            String.class));
        } finally {
            remoteRelease.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void quantityLockRechecksFormalAuthorityInsteadOfUsingAnEarlierPassedDecision()
            throws Exception {
        var stale = gate.sync("P1");
        assertTrue(stale.getPassed());
        receive(first());
        assertEquals("CONFLICT", state());
        var result = quantities.reconcile("P1", stale);
        assertFalse(result.getPassed());
        quantity("quality_pending_num", 5);
        quantity("quality_released_num", 0);
    }

    @Test
    void alreadyReceivedLaterEvidencePreventsReleasingAnIntermediatePass() throws Exception {
        var pass = result(first(), "QUALIFIED", 5, 5);
        var changed = result(pass, "UN_QUALIFIED", 0, 0);
        receive(first());
        receive(pass);
        receive(changed);
        assertTrue(worker.consume(10L));
        assertTrue(worker.consume(10L));
        quantity("quality_released_num", 0);
        quantity("quality_pending_num", 5);
        assertFalse(worker.consume(10L));
        assertEquals("CONFLICT", state());
        assertEquals(2, countApplications());
    }

    @Test
    void appliedPassDoesNotAuthorizeNewPendingOutputAfterANewUnappliedEvent() throws Exception {
        receive(first());
        assertTrue(worker.consume(10L));
        var pass = result(first(), "QUALIFIED", 5, 5);
        receive(pass);
        assertTrue(worker.consume(10L));
        var stale = gate.sync("P1");
        assertTrue(stale.getPassed());
        db.update("UPDATE mes_task SET notice_num=7,complete_num=7 WHERE id=1");
        db.update("UPDATE mes_produce_task SET notice_num=7,complete_num=7 WHERE id=21");
        db.update("UPDATE mes_task_arrange SET notice_num=7,complete_num=7 WHERE id=11");
        db.update(
                "INSERT INTO"
                    + " mes_task_report(id,tenant_id,factory_code,task_code,produce_task_code,arrange_code,report_num,quality_released_num,quality_pending_num,quality_rejected_num,quality_quantity_status,status,report_time)"
                    + " VALUES(102,1,'F1','T1','P1','A1',2,0,2,0,'PENDING','EFFECTIVE',CURRENT_TIMESTAMP)");
        db.update(
                "INSERT INTO"
                    + " mes_task_report_record(id,tenant_id,factory_code,report_id,type,quality_released_num,quality_pending_num,quality_rejected_num)"
                    + " VALUES(103,1,'F1',102,'REPORT',0,2,0)");
        db.update(
                "INSERT INTO"
                    + " mes_warehouse_live_stock(id,tenant_id,factory_code,source_report_id,mat_code,mat_unit,num,inspection_status,status)"
                    + " VALUES(202,1,'F1',102,'MAT1','PCS',2,'PENDING','1')");
        receive(result(pass, "UN_QUALIFIED", 0, 0));
        assertFalse(gate.sync("P1").getPassed());
        assertFalse(quantities.reconcile("P1", stale).getPassed());
        quantity("quality_released_num", 5);
        assertEquals(
                0,
                BigDecimal.valueOf(2)
                        .compareTo(
                                db.queryForObject(
                                        "SELECT quality_pending_num FROM mes_task_report WHERE"
                                                + " id=102",
                                        BigDecimal.class)));
        assertEquals(
                "PENDING",
                db.queryForObject(
                        "SELECT inspection_status FROM mes_warehouse_live_stock WHERE id=202",
                        String.class));
    }

    @Test
    void mandatoryIntensifiedInspectionStillControlsAnOrdinaryNonePolicy() throws Exception {
        rule.setGateMode("NONE");
        seed(30, 900, "MIDDLE");
        var point =
                new MesChangePointHandleEntity()
                        .setProduceTaskCode("P1")
                        .setIntensifiedInspectionRecordId(30L)
                        .setEventCode("CHANGE-1");
        point.setTenantId(1L);
        point.setFactoryCode("F1");
        point.setStatus("M");
        when(changes.selectList(any())).thenReturn(List.of(point));
        var middle = bodies.get(900L);
        receive(middle);
        assertTrue(worker.consume(30L));
        quantity("quality_pending_num", 5);
        receive(result(middle, "QUALIFIED", 5, 5));
        assertTrue(worker.consume(30L));
        quantity("quality_released_num", 5);
        assertEquals(0, remoteCalls);
    }

    @Test
    void ordinaryNoneDoesNotReleaseOldPendingOutputAsInspectionPassed() throws Exception {
        rule.setGateMode("NONE");
        receive(first());
        assertTrue(worker.consume(10L));
        quantity("quality_pending_num", 5);
        quantity("quality_released_num", 0);
    }

    @Test
    void aRealAppendBeforeJudgementCanAdvanceWithoutInventingQualifiedPieces() throws Exception {
        receive(first());
        assertTrue(worker.consume(10L));
        var append = next(first());
        append.put("eventType", "APPEND");
        append.put("quantity", 2);
        ((ObjectNode) append.get("source")).put("num", 7).put("status", "RUNNING");
        receive(append);
        assertTrue(worker.consume(10L));
        assertEquals(2L, sequence());
        quantity("quality_pending_num", 5);
        assertEquals(0, remoteCalls);
    }

    @Test
    void postJudgementAppendRequiresAnAuthorizedRevision() throws Exception {
        receive(first());
        assertTrue(worker.consume(10L));
        var pass = result(first(), "QUALIFIED", 5, 5);
        receive(pass);
        assertTrue(worker.consume(10L));
        var append = next(pass);
        append.put("eventType", "APPEND");
        append.put("quantity", 2);
        ((ObjectNode) append.get("source")).put("num", 7).put("status", "RUNNING");
        receive(append);
        assertFalse(worker.consume(10L));
        assertEquals("CONFLICT", state());
        quantity("quality_released_num", 5);
    }

    @Test
    void explicitPartialDispositionUsesTheExistingStockSplitRules() throws Exception {
        receive(first());
        assertTrue(worker.consume(10L));
        receive(result(first(), "UN_QUALIFIED", 2, 3));
        assertTrue(worker.consume(10L));
        quantity("quality_released_num", 2);
        quantity("quality_rejected_num", 1);
        quantity("quality_pending_num", 2);
        assertEquals(
                3,
                db.queryForObject("SELECT COUNT(*) FROM mes_warehouse_live_stock", Integer.class));
    }

    @Test
    void backgroundApplicationRequiresNoHumanPrincipalAndRestoresItsScope() throws Exception {
        receive(first());
        var dispatcher = new MesInspectionEventApplicationDispatcher(states, worker, planOutbox);
        assertThrows(RuntimeException.class, () -> dispatcher.scan());
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        TenantContextHolder.setTenantId(9L);
        TenantContextHolder.setTenantSkip();
        FactoryContextHolder.setFactoryCode("OLD");
        FactoryContextHolder.setFactorySkip();
        assertEquals(1, dispatcher.scan());
        assertEquals(9L, TenantContextHolder.getTenantId());
        assertTrue(TenantContextHolder.getTenantSkip());
        assertEquals("OLD", FactoryContextHolder.getFactoryCode());
        assertTrue(FactoryContextHolder.getFactorySkip());
        assertEquals(1L, sequence());
        quantity("quality_pending_num", 5);
    }

    @Test
    void twoIndependentWorkersApplyTheSameOriginalOnlyOnce() throws Exception {
        receive(first());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> consumeInScope());
            var b = pool.submit(() -> consumeInScope());
            assertNotEquals(
                    a.get(5, java.util.concurrent.TimeUnit.SECONDS),
                    b.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, countApplications());
            assertEquals(1L, sequence());
        } finally {
            pool.shutdownNow();
        }
    }

    boolean consumeInScope() {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        try {
            return worker.consume(10L);
        } finally {
            TenantContextHolder.clear();
            FactoryContextHolder.clear();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TENANT", "FACTORY", "SKIP", "FACTORY_SKIP"})
    void aWorkerCannotApplyAcrossAnIncorrectOrganization(String bad) throws Exception {
        receive(first());
        switch (bad) {
            case "TENANT" -> TenantContextHolder.setTenantId(2L);
            case "FACTORY" -> FactoryContextHolder.setFactoryCode("OTHER");
            case "SKIP" -> TenantContextHolder.setTenantSkip();
            case "FACTORY_SKIP" -> FactoryContextHolder.setFactorySkip();
        }
        try {
            worker.consume(10L);
        } catch (RuntimeException expected) {
        }
        assertEquals(0, countApplications());
        quantity("quality_pending_num", 5);
    }

    @Test
    void aCanceledUnjudgedEventDoesNotInventAFullBatchRejection() throws Exception {
        receive(first());
        assertTrue(worker.consume(10L));
        var canceled = next(first());
        canceled.put("eventType", "CANCELED");
        ((ObjectNode) canceled.get("source")).put("result", "CANCELED").put("status", "FINISHED");
        receive(canceled);
        assertTrue(worker.consume(10L));
        quantity("quality_pending_num", 5);
        quantity("quality_rejected_num", 0);
        assertEquals(
                "CANCELED",
                db.queryForObject(
                        "SELECT gate_status FROM mom_mes.mes_inspection_gen_record WHERE id=10",
                        String.class));
    }

    @Test
    void actualReadViewPagesFrozenReceiptsAndRechecksFactoryAuthorization() throws Exception {
        receive(first());
        assertTrue(worker.consume(10L));
        receive(result(first(), "QUALIFIED", 5, 5));
        assertTrue(worker.consume(10L));
        var factories = mock(com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService.class);
        when(factories.check(any())).thenReturn(R.ok(true));
        authenticate();
        var query =
                proxy(
                        new MesInspectionEventApplicationQuery(
                                states,
                                projection,
                                new MesActualFactoryAccessService(factories),
                                session.getMapper(MesSourceQuantityPlanOutboxMapper.class)));
        var page = query.get(10L, 0, 1);
        assertEquals(2, page.total());
        assertEquals(1, page.applications().size());
        assertEquals(1, page.nextAfter());
        assertEquals(2L, page.receivedThrough());
        assertEquals(9700L, page.applications().get(0).getId());
        var last = query.get(10L, page.nextAfter(), 1);
        assertEquals(9701L, last.applications().get(0).getId());
        assertEquals(2, last.nextAfter());
        assertTrue(query.get(10L, 2, 50).applications().isEmpty());
        when(factories.check(any())).thenReturn(R.ok(false));
        assertThrows(RuntimeException.class, () -> query.get(10L, 0, 50));
        when(factories.check(any())).thenReturn(R.ok(true));
        FactoryContextHolder.setFactoryCode("OTHER");
        assertThrows(RuntimeException.class, () -> query.get(10L, 0, 50));
        FactoryContextHolder.setFactoryCode("F1");
        for (long[] range :
                List.of(
                        new long[] {-1, 50},
                        new long[] {5001, 50},
                        new long[] {0, 0},
                        new long[] {0, 101}))
            assertThrows(RuntimeException.class, () -> query.get(10L, range[0], (int) range[1]));
        assertEquals(2, countApplications());
        quantity("quality_released_num", 5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP", "IDENTITY", "TRANSACTION"})
    void aHumanOrBusinessTransactionCannotStartTheCrossOrganizationApplicationScan(String boundary)
            throws Exception {
        receive(first());
        var dispatch = new MesInspectionEventApplicationDispatcher(states, worker, planOutbox);
        try {
            if (boundary.equals("HTTP"))
                org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                        new org.springframework.web.context.request.ServletRequestAttributes(
                                new org.springframework.mock.web.MockHttpServletRequest()));
            if (boundary.equals("IDENTITY")) authenticate();
            if (boundary.equals("TRANSACTION"))
                assertThrows(
                        RuntimeException.class,
                        () ->
                                new org.springframework.transaction.support.TransactionTemplate(tx)
                                        .execute(t -> dispatch.scan()));
            else assertThrows(RuntimeException.class, dispatch::scan);
            assertEquals(0, countApplications());
            assertEquals(0L, sequence());
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void actualMvcApplicationReadRequiresTheExistingShowMenu() throws Exception {
        receive(first());
        assertTrue(worker.consume(10L));
        authenticate();
        var factories = mock(com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService.class);
        when(factories.check(any())).thenReturn(R.ok(true));
        var query =
                proxy(
                        new MesInspectionEventApplicationQuery(
                                states,
                                projection,
                                new MesActualFactoryAccessService(factories),
                                session.getMapper(MesSourceQuantityPlanOutboxMapper.class)));
        var permissions = new MenuPermissions();
        var web = new org.springframework.web.context.support.GenericWebApplicationContext();
        web.setServletContext(new org.springframework.mock.web.MockServletContext());
        org.springframework.context.annotation.AnnotationConfigUtils
                .registerAnnotationConfigProcessors(web);
        web.registerBean(ApplicationWebConfiguration.class);
        web.registerBean("pms", MenuPermissions.class, () -> permissions);
        web.registerBean(
                com.kitex.mom.mes.controller.MesInspectionEventApplicationController.class,
                () ->
                        new com.kitex.mom.mes.controller.MesInspectionEventApplicationController(
                                query));
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
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                            "/mesInspectionEventApplication/10")
                                    .param("limit", "1"))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .status()
                                    .isOk())
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .jsonPath("$.data.total")
                                    .value(1));
            permissions.allowed = false;
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                    "/mesInspectionEventApplication/10"))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .status()
                                    .isForbidden());
            assertEquals(1, countApplications());
        } finally {
            web.close();
        }
    }

    public static class MenuPermissions {
        boolean allowed = true;

        public boolean hasPermission(String permission) {
            return allowed && permission.equals("mesInspectionGenRecord:show");
        }
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
    static class ApplicationWebConfiguration {}

    void authenticate() {
        var actor =
                new com.kitex.common.security.service.MomUser(
                        7L,
                        "operator",
                        1L,
                        null,
                        null,
                        null,
                        "Operator",
                        null,
                        1L,
                        "unused",
                        true,
                        true,
                        "USER",
                        true,
                        true,
                        List.of());
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(actor, "unused", List.of()));
    }

    @Test
    void backgroundUsesRealAuthorizedLockBoundaryWithoutInventingAPrincipal() throws Exception {
        var factories = mock(com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService.class);
        var authorized =
                proxy(
                        new MesIdentifierBindingGroupServiceImpl(
                                new MesActualFactoryAccessService(factories),
                                session.getMapper(MesTaskMapper.class),
                                session.getMapper(MesProduceTaskMapper.class),
                                mock(MesIdentifierNodePolicyValidator.class),
                                mutex,
                                holds));
        ReflectionTestUtils.setField(quantities, "bindingGroups", authorized);

        receive(first());
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        assertEquals(
                1, new MesInspectionEventApplicationDispatcher(states, worker, planOutbox).scan());
        assertEquals(1L, sequence());
        org.mockito.Mockito.verifyNoMoreInteractions(factories);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryQuantityLocksEvidenceBeforeFirstOrLaterReceipt(boolean managed) throws Exception {
        MesQualityGateDecision stale;
        ObjectNode incoming;
        if (managed) {
            receive(first());
            assertTrue(worker.consume(10L));
            var pass = result(first(), "QUALIFIED", 5, 5);
            receive(pass);
            assertTrue(worker.consume(10L));
            // 新有效报工在前次判定后产生；保持已释放历史，不逆改它。
            db.update(
                    "INSERT INTO"
                        + " mes_task_report(id,tenant_id,factory_code,task_code,produce_task_code,arrange_code,report_num,quality_released_num,quality_pending_num,quality_rejected_num,quality_quantity_status,status,report_time)"
                        + " VALUES(102,1,'F1','T1','P1','A1',0,0,0,0,'PENDING','EFFECTIVE',CURRENT_TIMESTAMP)");
            incoming = result(pass, "UN_QUALIFIED", 0, 0);
            stale = gate.sync("P1");
        } else {
            stale = gate.sync("P1");
            incoming = first();
        }
        var paused = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        fault.afterControl =
                () -> {
                    paused.countDown();
                    assertTrue(resume.await(5, java.util.concurrent.TimeUnit.SECONDS));
                };
        var authentication =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Future<?> quantity = null, receipt = null;
        try {
            quantity =
                    pool.submit(
                            () -> {
                                Thread.currentThread().setName("ordinary-quantity");
                                TenantContextHolder.setTenantId(1L);
                                FactoryContextHolder.setFactoryCode("F1");
                                org.springframework.security.core.context.SecurityContextHolder
                                        .getContext()
                                        .setAuthentication(authentication);
                                try {
                                    quantities.reconcile("P1", stale);
                                } finally {
                                    TenantContextHolder.clear();
                                    FactoryContextHolder.clear();
                                    org.springframework.security.core.context.SecurityContextHolder
                                            .clearContext();
                                }
                            });
            assertTrue(paused.await(5, java.util.concurrent.TimeUnit.SECONDS));
            receipt =
                    pool.submit(
                            () -> {
                                TenantContextHolder.setTenantId(1L);
                                FactoryContextHolder.setFactoryCode("F1");
                                try {
                                    receive(incoming);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                } finally {
                                    TenantContextHolder.clear();
                                    FactoryContextHolder.clear();
                                }
                            });
            var concurrentReceipt = receipt;
            assertThrows(
                    java.util.concurrent.TimeoutException.class,
                    () -> concurrentReceipt.get(200, java.util.concurrent.TimeUnit.MILLISECONDS),
                    "收件必须等待数量事务持有的生成记录锁");
        } finally {
            resume.countDown();
            fault.afterControl = null;
            if (quantity != null) quantity.get(5, java.util.concurrent.TimeUnit.SECONDS);
            if (receipt != null) receipt.get(5, java.util.concurrent.TimeUnit.SECONDS);
            pool.shutdownNow();
        }
    }

    @Test
    void fiftyPoisonedStreamsYieldTheNextHealthyStreamOnTheFollowingScan() throws Exception {
        receive(first());
        for (int i = 1; i <= 50; i++) {
            long record = 1000 + i, task = 700 + i;
            db.update(
                    "INSERT INTO"
                        + " mom_mes.mes_inspection_gen_record(id,tenant_id,factory_code,task_code,produce_task_code,inspection_id,gen_flag,del_flag,quality_event_managed)"
                        + " VALUES(?,1,'F1','T1','P1',?,'1','0',true)",
                    record,
                    task);
            db.update(
                    "INSERT INTO"
                        + " mom_mes.mes_inspection_event_state(id,tenant_id,factory_code,inspection_id,task_code,produce_task_code,last_sequence,status,version,created_at,updated_at)"
                        + " VALUES(?,1,'F1',?,'T1','P1',0,'ACTIVE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    record,
                    task);
            db.update(
                    "INSERT INTO"
                        + " mom_mes.mes_inspection_event_inbox(id,tenant_id,factory_code,inspection_type,task_id,sequence_no,gen_record_id)"
                        + " VALUES(?,1,'F1','PROCEDURE',?,1,?)",
                    10000 + i,
                    task,
                    record);
        }
        var poison = mock(MesInspectionEventApplicationWorker.class);
        when(poison.consume(anyLong())).thenThrow(new IllegalStateException("missing mirror"));
        org.mockito.Mockito.doReturn(true).when(poison).consume(1050L);
        var dispatch = new MesInspectionEventApplicationDispatcher(states, poison, planOutbox);
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        assertEquals(0, dispatch.scan());
        assertEquals(1, dispatch.scan());
    }

    @Test
    void planIsNotCalledFromAnApplicationThatLaterRollsBack() throws Exception {
        realSourceQuantities();
        db.update("UPDATE mes_task SET plan_no='PLAN1' WHERE id=1");
        receive(first());
        assertTrue(worker.consume(10L));
        receive(result(first(), "UN_QUALIFIED", 0, 3));
        fault.kind = "RECEIPT";
        assertThrows(RuntimeException.class, () -> worker.consume(10L));
        assertEquals(0, planCalls, "PLAN不能先于MES本地整体提交");
        quantity("quality_pending_num", 5);
    }

    @Test
    void plannedRejectionCommitsItsLocalFactWithoutCallingPlanInsideTheApplication()
            throws Exception {
        realSourceQuantities();
        db.update("UPDATE mes_task SET plan_no='PLAN1' WHERE id=1");
        receive(first());
        assertTrue(worker.consume(10L));
        receive(result(first(), "UN_QUALIFIED", 0, 3));
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        assertTrue(worker.consume(10L));
        quantity("quality_rejected_num", 3);
        assertEquals(0, planCalls, "正式事件只能登记可靠待发，不能在本地事务内写PLAN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLAN_OUTBOX", "SOURCE_FACT", "RECEIPT", "CURSOR", "MIRROR", "CACHE"})
    void plannedRejectionKeepsFactsOutboxAndApplicationInTheSameTransaction(String failure)
            throws Exception {
        realSourceQuantities();
        db.update("UPDATE mes_task SET plan_no='PLAN1' WHERE id=1");
        receive(first());
        assertTrue(worker.consume(10L));
        receive(result(first(), "UN_QUALIFIED", 0, 3));
        fault.kind = failure;
        assertThrows(RuntimeException.class, () -> worker.consume(10L));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mes_aps_source_quantity_event", Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_mes.mes_source_quantity_plan_outbox",
                        Integer.class));
        assertEquals(1, countApplications());
        assertEquals(1, sequence());
        quantity("quality_pending_num", 5);
        assertEquals(0, planCalls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "EMPTY", "FAILURE_DATA", "SAVE_ZERO"})
    void committedPlanOriginalRecoversUnknownOrMissingAcknowledgement(String mode)
            throws Exception {
        realSourceQuantities();
        db.update("UPDATE mes_task SET plan_no='PLAN1' WHERE id=1");
        receive(first());
        assertTrue(worker.consume(10L));
        receive(result(first(), "UN_QUALIFIED", 0, 3));
        assertTrue(worker.consume(10L));
        assertEquals(0, planCalls);
        long id =
                db.queryForObject(
                        "SELECT id FROM mom_mes.mes_source_quantity_plan_outbox", Long.class);
        var original =
                db.queryForObject(
                        "SELECT payload FROM mom_mes.mes_source_quantity_plan_outbox",
                        String.class);
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        planMode = mode;
        if (mode.equals("SAVE_ZERO")) {
            fault.kind = "PLAN_PROGRESS";
            assertThrows(RuntimeException.class, () -> planOutbox.deliver(id));
            assertEquals(
                    "PENDING",
                    db.queryForObject(
                            "SELECT status FROM mom_mes.mes_source_quantity_plan_outbox",
                            String.class));
            fault.kind = "";
        } else {
            assertFalse(planOutbox.deliver(id));
            assertEquals(
                    "RETRY_WAIT",
                    db.queryForObject(
                            "SELECT status FROM mom_mes.mes_source_quantity_plan_outbox",
                            String.class));
            assertFalse(planOutbox.deliver(id));
            assertEquals(1, planCalls, "未到重试时间不能重发");
        }
        var restarted =
                proxy(
                        new MesSourceQuantityPlanOutbox(
                                session.getMapper(MesSourceQuantityPlanOutboxMapper.class),
                                session.getMapper(MesReworkSourcePlanOutboxMapper.class),
                                planRemote));
        ReflectionTestUtils.setField(
                restarted,
                "clock",
                java.time.Clock.offset(
                        java.time.Clock.systemUTC(), java.time.Duration.ofMinutes(2)));
        planMode = "OK";
        assertTrue(restarted.deliver(id));
        assertFalse(restarted.deliver(id));
        assertEquals(2, planCalls);
        assertEquals(1, planAccepted.size());
        assertEquals(0, BigDecimal.valueOf(3).compareTo(planAccepted.values().iterator().next()));
        assertEquals(
                original,
                db.queryForObject(
                        "SELECT payload FROM mom_mes.mes_source_quantity_plan_outbox",
                        String.class));
        assertEquals(planPayloads.get(0), planPayloads.get(1));
        quantity("quality_rejected_num", 3);
        assertEquals(2, countApplications());
    }

    @Test
    void planNotificationReadIsScopedAndDoesNotExposeOrModifyRawPayload() throws Exception {
        realSourceQuantities();
        db.update("UPDATE mes_task SET plan_no='PLAN1' WHERE id=1");
        receive(first());
        assertTrue(worker.consume(10L));
        receive(result(first(), "UN_QUALIFIED", 0, 3));
        assertTrue(worker.consume(10L));
        var factories = mock(com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService.class);
        when(factories.check(any())).thenReturn(R.ok(true));
        authenticate();
        var query =
                proxy(
                        new MesInspectionEventApplicationQuery(
                                states,
                                projection,
                                new MesActualFactoryAccessService(factories),
                                session.getMapper(MesSourceQuantityPlanOutboxMapper.class)));
        var page = query.planDeliveries(10L, 0, 1);
        assertEquals(1, page.size());
        assertNull(page.get(0).getPayload());
        assertEquals("PENDING", page.get(0).getStatus());
        assertTrue(query.planDeliveries(10L, page.get(0).getId(), 1).isEmpty());
        when(factories.check(any())).thenReturn(R.ok(false));
        assertThrows(RuntimeException.class, () -> query.planDeliveries(10L, 0, 1));
        assertEquals(0, planCalls);
    }

    int planCalls;
    String planMode = "OK";
    com.kitex.mom.plan.feign.RemotePlnIssueService planRemote;
    Map<String, BigDecimal> planAccepted = new HashMap<>();
    List<String> planPayloads = new ArrayList<>();

    void realSourceQuantities() throws Exception {
        var c = session.getConfiguration();
        for (var type :
                List.of(MesApsBatchAllocationEntity.class, MesApsSourceQuantityEventEntity.class))
            ReflectionTestUtils.invokeMethod(stock, "table", c, type);
        c.addMapper(MesApsBatchAllocationMapper.class);
        c.addMapper(MesApsSourceQuantityEventMapper.class);
        db.execute(
                "CREATE ALIAS IF NOT EXISTS HASHTEXTEXTENDED FOR"
                    + " 'com.kitex.mom.mes.service.impl.MesInspectionEventApplicationTransactionTest.lockHash'");
        db.execute(
                "CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK FOR"
                    + " 'com.kitex.mom.mes.service.impl.MesInspectionEventApplicationTransactionTest.lockTable'");
        var plan = mock(com.kitex.mom.plan.feign.RemotePlnIssueService.class);
        planRemote = plan;
        when(plan.edit(anyList()))
                .thenAnswer(
                        call -> {
                            planCalls++;
                            List<com.kitex.mom.oms.dto.OmsOrderLifeCycleDTO> callbacks =
                                    call.getArgument(0);
                            planPayloads.add(JSON.writeValueAsString(callbacks));
                            if (planMode.equals("EMPTY")) return R.ok(null);
                            if (planMode.equals("FAILURE_DATA")) return R.ok(true).setCode(1);
                            for (var callback : callbacks)
                                planAccepted.putIfAbsent(
                                        callback.getRequestId(), callback.getQualityRejectedNum());
                            if (planMode.equals("UNKNOWN"))
                                throw new IllegalStateException(
                                        "remote committed but response lost");
                            return R.ok(true);
                        });
        planOutbox =
                proxy(
                        new MesSourceQuantityPlanOutbox(
                                session.getMapper(MesSourceQuantityPlanOutboxMapper.class),
                                session.getMapper(MesReworkSourcePlanOutboxMapper.class), plan));
        ReflectionTestUtils.setField(
                quantities,
                "sourceQuantityService",
                proxy(
                        new MesApsSourceQuantityServiceImpl(
                                session.getMapper(MesTaskMapper.class),
                                session.getMapper(MesApsBatchAllocationMapper.class),
                                session.getMapper(MesApsSourceQuantityEventMapper.class),
                                plan,
                                planOutbox)));
    }

    public static long lockHash(String key, int seed) {
        return key == null ? 0 : key.hashCode();
    }

    public static java.sql.ResultSet lockTable(java.sql.Connection c, Long key) throws Exception {
        var r = new org.h2.tools.SimpleResultSet();
        r.addColumn("locked", java.sql.Types.INTEGER, 10, 0);
        if (!c.getMetaData().getURL().equals("jdbc:columnlist:connection"))
            try (var s = c.createStatement();
                    var locked =
                            s.executeQuery(
                                    "SELECT task_code FROM business_lock WHERE task_code='T1' FOR"
                                            + " UPDATE")) {
                assertTrue(locked.next());
            }
        r.addRow(1);
        return r;
    }

    ObjectNode first() {
        return bodies.get(700L);
    }

    ObjectNode result(ObjectNode previous, String verdict, int usable, int handled)
            throws Exception {
        var n = next(previous);
        n.put("eventType", "RESULT");
        n.put("qualified", verdict.equals("QUALIFIED"));
        var source = (ObjectNode) n.get("source");
        source.put("result", verdict);
        source.put("status", verdict.equals("QUALIFIED") ? "FINISHED" : "UNHANDLED");
        source.put("usableNum", usable);
        source.put("handleNum", handled);
        return n;
    }

    ObjectNode next(ObjectNode previous) throws Exception {
        var n = previous.deepCopy();
        n.put("eventId", Long.toString(Long.parseLong(previous.get("eventId").asText()) + 1));
        n.put("historyId", Long.toString(Long.parseLong(previous.get("historyId").asText()) + 1));
        n.put("sequenceNo", previous.get("sequenceNo").asLong() + 1);
        n.put("previousEventId", previous.get("eventId").asText());
        n.put("previousFingerprint", hash(JSON.writeValueAsString(previous)));
        return n;
    }

    void receive(ObjectNode body) throws Exception {
        String raw = JSON.writeValueAsString(body);
        var command =
                new MesInspectionEventDeliveryDTO()
                        .setEventId(Long.valueOf(body.get("eventId").asText()))
                        .setTenantId(1L)
                        .setFactoryCode("F1")
                        .setPayload(raw)
                        .setFingerprint(hash(raw))
                        .setIssuedAt(Instant.now().getEpochSecond())
                        .setNonce(UUID.randomUUID().toString());
        command.setSignature(command.sign(KEY));
        assertNotNull(inbox.accept(command));
    }

    String state() {
        return db.queryForObject(
                "SELECT status FROM mom_mes.mes_inspection_event_state WHERE id=10", String.class);
    }

    long sequence() {
        return db.queryForObject(
                "SELECT last_sequence FROM mom_mes.mes_inspection_event_state WHERE id=10",
                Long.class);
    }

    int countApplications() {
        return db.queryForObject(
                "SELECT COUNT(*) FROM mom_mes.mes_inspection_event_application", Integer.class);
    }

    void quantity(String name, int expected) {
        assertEquals(
                0,
                BigDecimal.valueOf(expected)
                        .compareTo(
                                db.queryForObject(
                                        "SELECT " + name + " FROM mes_task_report WHERE id=100",
                                        BigDecimal.class)));
    }

    static QmsInspectionProcedureTaskEntity source(ObjectNode j) {
        var s = j.get("source");
        var q =
                new QmsInspectionProcedureTaskEntity()
                        .setSourceTaskCode(s.get("sourceTaskCode").asText())
                        .setSourceRequestId(s.get("sourceRequestId").asText())
                        .setSourceRequestFingerprint(s.get("sourceRequestFingerprint").asText())
                        .setLabelType(s.get("labelType").asText())
                        .setMatCode("MAT1")
                        .setProcessCode("R1")
                        .setStandardId(9L)
                        .setBatchNo("B1")
                        .setOrderNo("T1")
                        .setOrderId("1")
                        .setNum(5);
        q.setId(Long.valueOf(j.get("taskId").asText()));
        q.setTenantId(1L);
        q.setFactoryCode("F1");
        return q;
    }

    static String hash(String raw) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(raw.getBytes(StandardCharsets.UTF_8)));
    }

    <T> T proxy(T target) {
        var p = new ProxyFactory(target);
        p.setProxyTargetClass(true);
        p.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        return (T) p.getProxy();
    }

    @Intercepts({
        @Signature(
                type = Executor.class,
                method = "query",
                args = {
                    MappedStatement.class,
                    Object.class,
                    org.apache.ibatis.session.RowBounds.class,
                    org.apache.ibatis.session.ResultHandler.class
                }),
        @Signature(
                type = Executor.class,
                method = "update",
                args = {MappedStatement.class, Object.class})
    })
    static class WriteFault implements Interceptor {
        String kind = "";
        Throwing afterControl;

        interface Throwing {
            void run() throws Exception;
        }

        public Object intercept(Invocation i) throws Throwable {
            String id = ((MappedStatement) i.getArgs()[0]).getId();
            if (i.getMethod().getName().equals("query")) {
                var answer = i.proceed();
                if (id.endsWith("MesInspectionEventStateMapper.controlled")
                        && Thread.currentThread().getName().equals("ordinary-quantity")
                        && afterControl != null) {
                    var callback = afterControl;
                    afterControl = null;
                    callback.run();
                }
                return answer;
            }
            if ((kind.equals("PLAN_PROGRESS")
                            && id.endsWith("MesSourceQuantityPlanOutboxMapper.progress"))
                    || (kind.equals("PLAN_OUTBOX")
                            && id.endsWith("MesSourceQuantityPlanOutboxMapper.insert"))
                    || (kind.equals("SOURCE_FACT")
                            && id.endsWith("MesApsSourceQuantityEventMapper.insert"))
                    || (kind.equals("MIRROR") && id.endsWith("MesTaskReportRecordMapper.update"))
                    || (kind.equals("RECEIPT")
                            && id.endsWith("MesInspectionEventStateMapper.append"))
                    || (kind.equals("CURSOR")
                            && id.endsWith("MesInspectionEventStateMapper.update"))
                    || (kind.equals("CACHE")
                            && (id.contains("MesInspectionGenRecordMapper.update")
                                    || id.endsWith("MesInspectionEventStateMapper.project")))
                    || (kind.equals("STOCK") && id.contains("MesWarehouseLiveStockMapper.update")))
                return 0;
            return i.proceed();
        }
    }
}
