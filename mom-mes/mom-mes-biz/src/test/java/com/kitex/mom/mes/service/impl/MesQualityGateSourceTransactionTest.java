package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.*;
import com.kitex.mom.qms.api.feign.RemoteProcedureService;
import com.kitex.mom.qms.dto.inspection.InspectionProcedureCreateParam;
import com.kitex.mom.qms.dto.inspection.InspectionProcedureSourceContract;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/** 真实普通质量门、生成记录及永久创建原件、MyBatis和事务；QMS网络和规则配置端口受控。 */
class MesQualityGateSourceTransactionTest {
    private JdbcTemplate db;
    private com.kitex.mom.qms.entity.QmsQualityStandardEntity currentStandard;
    private String currentShift = "DAY";
    private final List<String> outboundSnapshots = new ArrayList<>();
    private final List<String> created = new ArrayList<>();
    private SqlSessionTemplate session;
    private DataSourceTransactionManager tx;
    private MesQualityGateService gate;
    private MesInspectionRuleEntity rule;
    private final Map<Long, QmsInspectionProcedureTaskEntity> sources = new LinkedHashMap<>();
    private final WriteFault fault = new WriteFault();
    private int responseCode;
    private boolean nullResponse, remoteThrows, duplicate, extra;
    private CountDownLatch firstRead, releaseFirst;

    @BeforeEach
    void setup() throws Exception {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:gate_source_"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        db = new JdbcTemplate(ds);
        db.execute("CREATE SCHEMA mom_mes");
        db.execute(
                "CREATE ALIAS mom_mes.mom_qms_inspection_command_ready_v1 AS 'Boolean"
                        + " ready(){return true;}'");
        var config = new MybatisConfiguration();
        var global = new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(
                new com.kitex.common.data.mybatis.MybatisPlusMetaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(config, global);
        for (var type :
                List.of(
                        MesTaskEntity.class,
                        MesProduceTaskEntity.class,
                        MesInspectionGenRecordEntity.class,
                        MesQmsInspectionCommandEntity.class,
                        MesChangePointHandleEntity.class)) table(config, type);
        for (var type :
                List.of(
                        MesTaskMapper.class,
                        MesQualityGateSourceMapper.class,
                        MesProduceTaskMapper.class,
                        MesInspectionGenRecordMapper.class,
                        MesQmsInspectionCommandMapper.class,
                        MesChangePointHandleMapper.class)) config.addMapper(type);
        var tenant = new MybatisPlusInterceptor();
        tenant.addInnerInterceptor(
                new TenantLineInnerInterceptor(
                        new TenantLineHandler() {
                            @Override
                            public net.sf.jsqlparser.expression.Expression getTenantId() {
                                return new net.sf.jsqlparser.expression.LongValue(
                                        Objects.requireNonNullElse(
                                                TenantContextHolder.getTenantId(), 0L));
                            }

                            @Override
                            public boolean ignoreTable(String table) {
                                return TenantContextHolder.getTenantSkip();
                            }
                        }));
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        factory.setGlobalConfig(global);
        factory.setPlugins(tenant, fault);
        session = new SqlSessionTemplate(factory.getObject());
        tx = new DataSourceTransactionManager(ds);
        var records = new MesInspectionGenRecordServiceImpl();
        ReflectionTestUtils.setField(
                records, "baseMapper", session.getMapper(MesInspectionGenRecordMapper.class));
        var produces = new Produces();
        ReflectionTestUtils.setField(
                produces, "baseMapper", session.getMapper(MesProduceTaskMapper.class));
        var produceService = bridge(MesProduceTaskService.class, produces);
        rule = new MesInspectionRuleEntity().setGateMode("BLOCK").setInspectionTypes("FIRST");
        rule.setTenantId(1L);
        rule.setFactoryCode("F1");
        rule.setStatus("1");
        rule.setCode("R1");
        var rules = mock(MesInspectionRuleService.class);
        when(rules.getRuleByMatCode(any())).thenAnswer(c -> rule);
        var remote = mock(RemoteProcedureService.class);
        when(remote.getTaskByIds(anyList()))
                .thenAnswer(
                        call -> {
                            if (remoteThrows)
                                throw new IllegalStateException("test network unavailable");
                            if (nullResponse) return null;
                            List<Long> ids = call.getArgument(0);
                            var rows = new ArrayList<QmsInspectionProcedureTaskEntity>();
                            for (Long id : ids) {
                                var source = sources.get(id);
                                if (source != null) {
                                    var copy = new QmsInspectionProcedureTaskEntity();
                                    BeanUtils.copyProperties(source, copy);
                                    rows.add(copy);
                                }
                            }
                            if (duplicate && !rows.isEmpty()) rows.add(rows.get(0));
                            if (extra) {
                                var unknown = new QmsInspectionProcedureTaskEntity();
                                BeanUtils.copyProperties(sources.get(700L), unknown);
                                unknown.setId(999L);
                                rows.add(unknown);
                            }
                            if (firstRead != null
                                    && Thread.currentThread().getName().equals("late-source")) {
                                firstRead.countDown();
                                if (!releaseFirst.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("late source timeout");
                            }
                            return responseCode == 0
                                    ? R.ok(rows)
                                    : R.failed(rows, "test unavailable");
                        });
        var roots = new Roots();
        ReflectionTestUtils.setField(roots, "baseMapper", session.getMapper(MesTaskMapper.class));
        var rootService = bridge(MesTaskService.class, roots);
        var standards = mock(com.kitex.mom.qms.api.feign.RemoteQualityStandardService.class);
        currentStandard = new com.kitex.mom.qms.entity.QmsQualityStandardEntity();
        currentStandard.setId(9L);
        currentStandard.setInspectionMethod("ALL");
        when(standards.getRandomStandard(any())).thenAnswer(c -> R.ok(currentStandard));
        var classes = mock(com.kitex.mom.mdm.feign.RemoteClassesService.class);
        when(classes.getRuleBanci(any())).thenAnswer(c -> R.ok(currentShift));
        var batches = mock(com.kitex.mom.mes.batch.BatchGeneratorService.class);
        var batch = mock(com.kitex.mom.mes.batch.BatchGenerator.class);
        when(batch.generateBatchNumber(anyString(), anyString()))
                .thenAnswer(c -> "B-" + c.getArgument(1));
        when(batches.getBatchGenerator(any())).thenReturn(batch);
        var coordinator = mock(MesQmsInspectionCoordinator.class);
        when(coordinator.create(any(), anyString()))
                .thenAnswer(
                        call -> {
                            InspectionProcedureCreateParam command = call.getArgument(0);
                            String fingerprint =
                                    InspectionProcedureSourceContract.fingerprint(command);
                            String original = InspectionProcedureSourceContract.snapshot(command);
                            Integer count =
                                    db.queryForObject(
                                            "SELECT COUNT(*) FROM"
                                                    + " mom_mes.mes_qms_inspection_command WHERE"
                                                    + " request_id=?",
                                            Integer.class,
                                            command.getSourceRequestId());
                            if (count > 0) {
                                String frozen =
                                        db.queryForObject(
                                                "SELECT command_snapshot FROM"
                                                    + " mom_mes.mes_qms_inspection_command WHERE"
                                                    + " request_id=?",
                                                String.class,
                                                command.getSourceRequestId());
                                String hash =
                                        db.queryForObject(
                                                "SELECT command_fingerprint FROM"
                                                    + " mom_mes.mes_qms_inspection_command WHERE"
                                                    + " request_id=?",
                                                String.class,
                                                command.getSourceRequestId());
                                if (!original.equals(frozen) || !fingerprint.equals(hash))
                                    throw new IllegalStateException(
                                            "same request has a different frozen command");
                                String status =
                                        db.queryForObject(
                                                "SELECT status FROM"
                                                    + " mom_mes.mes_qms_inspection_command WHERE"
                                                    + " request_id=?",
                                                String.class,
                                                command.getSourceRequestId());
                                if ("QMS_CONFIRMED".equals(status))
                                    return db.queryForObject(
                                            "SELECT qms_task_id FROM"
                                                    + " mom_mes.mes_qms_inspection_command WHERE"
                                                    + " request_id=?",
                                            Long.class,
                                            command.getSourceRequestId());
                                if (!"QMS_CONFIRMED".equals(status)) {
                                    outboundSnapshots.add(original);
                                    var recover =
                                            new org.springframework.transaction.support
                                                    .TransactionTemplate(tx);
                                    recover.setPropagationBehavior(
                                            org.springframework.transaction.TransactionDefinition
                                                    .PROPAGATION_REQUIRES_NEW);
                                    recover.executeWithoutResult(
                                            t ->
                                                    db.update(
                                                            "UPDATE"
                                                                + " mom_mes.mes_qms_inspection_command"
                                                                + " SET status='QMS_CONFIRMED',qms_task_id=800"
                                                                + " WHERE request_id=?",
                                                            command.getSourceRequestId()));
                                }
                            } else outboundSnapshots.add(original);

                            if (db.queryForObject(
                                            "SELECT COUNT(*) FROM"
                                                    + " mom_mes.mes_qms_inspection_command WHERE"
                                                    + " request_id=?",
                                            Integer.class,
                                            command.getSourceRequestId())
                                    == 0) {
                                var independent =
                                        new org.springframework.transaction.support
                                                .TransactionTemplate(tx);
                                independent.setPropagationBehavior(
                                        org.springframework.transaction.TransactionDefinition
                                                .PROPAGATION_REQUIRES_NEW);
                                independent.executeWithoutResult(
                                        t ->
                                                db.update(
                                                        "INSERT INTO"
                                                            + " mom_mes.mes_qms_inspection_command(id,tenant_id,factory_code,request_id,source_task_code,command_version,command_snapshot,command_fingerprint,status,qms_task_id)"
                                                            + " VALUES(900,1,'F1',?,'P1',?,?,?,'QMS_CONFIRMED',800)",
                                                        command.getSourceRequestId(),
                                                        InspectionProcedureSourceContract.VERSION,
                                                        InspectionProcedureSourceContract.snapshot(
                                                                command),
                                                        fingerprint));
                                var q = new QmsInspectionProcedureTaskEntity();
                                BeanUtils.copyProperties(command, q);
                                q.setId(800L);
                                q.setTenantId(1L);
                                q.setFactoryCode("F1");
                                q.setSourceRequestFingerprint(fingerprint)
                                        .setStatus("WAIT")
                                        .setResult(null)
                                        .setUsableNum(0)
                                        .setHandleNum(0);
                                sources.put(800L, q);
                                created.add(command.getSourceRequestId());
                            }
                            return 800L;
                        });
        Constructor<?> constructor = MesQualityGateServiceImpl.class.getDeclaredConstructors()[0];
        Object[] args =
                Arrays.stream(constructor.getParameterTypes())
                        .map(
                                type -> {
                                    if (type == MesTaskService.class) return rootService;
                                    if (type
                                            == com.kitex.mom.qms.api.feign
                                                    .RemoteQualityStandardService.class)
                                        return standards;
                                    if (type == com.kitex.mom.mdm.feign.RemoteClassesService.class)
                                        return classes;
                                    if (type == com.kitex.mom.mes.batch.BatchGeneratorService.class)
                                        return batches;
                                    if (type == MesQmsInspectionCoordinator.class)
                                        return coordinator;
                                    if (type == MesQualityGateSourceEvidence.class)
                                        return new MesQualityGateSourceEvidence(
                                                session.getMapper(
                                                        MesQmsInspectionCommandMapper.class),
                                                session.getMapper(
                                                        MesQualityGateSourceMapper.class));
                                    if (type == MesProduceTaskService.class) return produceService;
                                    if (type == MesInspectionGenRecordService.class) return records;
                                    if (type == MesInspectionRuleService.class) return rules;
                                    if (type == RemoteProcedureService.class) return remote;
                                    if (type == MesChangePointHandleMapper.class)
                                        return session.getMapper(MesChangePointHandleMapper.class);
                                    return mock(type);
                                })
                        .toArray();
        Object target = constructor.newInstance(args);
        var proxy = new ProxyFactory(target);
        proxy.setInterfaces(MesQualityGateService.class);
        proxy.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        gate = (MesQualityGateService) proxy.getProxy();
        db.update(
                "INSERT INTO"
                    + " mom_mes.mes_produce_task(id,tenant_id,factory_code,code,task_code,mat_code,route_code,is_inspection,notice_num)"
                    + " VALUES(1,1,'F1','P1','T1','M1','OP1','1',5)");
        db.update(
                "INSERT INTO mom_mes.mes_task(id,tenant_id,factory_code,code)"
                        + " VALUES(100,1,'F1','T1')");
        seed(10L, 700L, "FIRST");
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    private void seed(long record, long qms, String phase) {
        String request = "MES_QMS_V2:P1:" + phase + ":" + record;
        var create =
                new InspectionProcedureCreateParam()
                        .setTaskCode("P1")
                        .setSourceTaskCode("P1")
                        .setSourceRequestId(request)
                        .setLabelType(phase)
                        .setMatCode("M1")
                        .setProcessCode("OP1")
                        .setStandardId(9L)
                        .setBatchNo("B1")
                        .setNum(5)
                        .setInspectionNum(1)
                        .setOrderNo("T1")
                        .setOrderId("100");
        String snapshot = InspectionProcedureSourceContract.snapshot(create),
                fingerprint = InspectionProcedureSourceContract.fingerprint(create);
        db.update(
                "INSERT INTO"
                    + " mom_mes.mes_inspection_gen_record(id,tenant_id,factory_code,task_code,produce_task_code,inspection_id,source_request_id,inspection_type,gen_flag,gate_status,blocked_count)"
                    + " VALUES(?,1,'F1','T1','P1',?,?,?,'1','PENDING',0)",
                record,
                qms,
                request,
                phase);
        db.update(
                "INSERT INTO"
                    + " mom_mes.mes_qms_inspection_command(id,tenant_id,factory_code,request_id,source_task_code,command_version,command_snapshot,command_fingerprint,status,qms_task_id)"
                    + " VALUES(?,1,'F1',?,'P1',?,?,?,'QMS_CONFIRMED',?)",
                record,
                request,
                InspectionProcedureSourceContract.VERSION,
                snapshot,
                fingerprint,
                qms);
        var source =
                new QmsInspectionProcedureTaskEntity()
                        .setSourceTaskCode("P1")
                        .setSourceRequestId(request)
                        .setSourceRequestFingerprint(fingerprint)
                        .setLabelType(phase)
                        .setMatCode("M1")
                        .setProcessCode("OP1")
                        .setStandardId(9L)
                        .setBatchNo("B1")
                        .setOrderNo("T1")
                        .setOrderId("100")
                        .setNum(5)
                        .setUsableNum(5)
                        .setHandleNum(5)
                        .setResult("QUALIFIED")
                        .setStatus("FINISHED");
        source.setId(qms);
        source.setTenantId(1L);
        source.setFactoryCode("F1");
        sources.put(qms, source);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FIRST", "MIDDLE", "LAST"})
    void aMatchingFrozenSourceCanReleaseTheConfiguredPhase(String phase) {
        phase(phase);
        var result = gate.sync("P1");
        assertTrue(result.getPassed());
        assertTrue(result.getAllowed());
        assertEquals("PASSED", value("gate_status", String.class));
        assertEquals("QUALIFIED", value("inspection_result", String.class));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "TENANT",
                "FACTORY",
                "REQUEST",
                "SOURCE_TASK",
                "ORDER",
                "PHASE",
                "MAT",
                "PROCESS",
                "STANDARD",
                "BATCH",
                "FINGERPRINT"
            })
    void aMatchingIdAloneCannotMakeAnotherSourcePass(String wrong) {
        var q = sources.get(700L);
        switch (wrong) {
            case "TENANT" -> q.setTenantId(2L);
            case "FACTORY" -> q.setFactoryCode("F2");
            case "REQUEST" -> q.setSourceRequestId("OTHER");
            case "SOURCE_TASK" -> q.setSourceTaskCode("P2");
            case "ORDER" -> q.setOrderNo("T2");
            case "PHASE" -> q.setLabelType("LAST");
            case "MAT" -> q.setMatCode("M2");
            case "PROCESS" -> q.setProcessCode("OP2");
            case "STANDARD" -> q.setStandardId(10L);
            case "BATCH" -> q.setBatchNo("B2");
            case "FINGERPRINT" -> q.setSourceRequestFingerprint("a".repeat(64));
        }
        var result = gate.sync("P1");
        assertFalse(result.getPassed());
        assertFalse(result.getAllowed());
        assertEquals("ERROR", value("gate_status", String.class));
        assertNull(value("qms_usable_num", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "MISSING",
                "WRONG_FACTORY",
                "UNCONFIRMED",
                "WRONG_ID",
                "WRONG_HASH",
                "RAW_CHANGED",
                "WRONG_VERSION"
            })
    void unprovenOrChangedCreationEvidenceCannotAuthorizeQuality(String wrong) {
        switch (wrong) {
            case "MISSING" -> db.update("DELETE FROM mom_mes.mes_qms_inspection_command");
            case "WRONG_FACTORY" ->
                    db.update("UPDATE mom_mes.mes_qms_inspection_command SET factory_code='F2'");
            case "UNCONFIRMED" ->
                    db.update(
                            "UPDATE mom_mes.mes_qms_inspection_command SET"
                                    + " status='OUTBOUND_STARTED'");
            case "WRONG_ID" ->
                    db.update("UPDATE mom_mes.mes_qms_inspection_command SET qms_task_id=701");
            case "WRONG_HASH" ->
                    db.update(
                            "UPDATE mom_mes.mes_qms_inspection_command SET command_fingerprint=?",
                            "a".repeat(64));
            case "RAW_CHANGED" ->
                    db.update(
                            "UPDATE mom_mes.mes_qms_inspection_command SET"
                                    + " command_snapshot='changed'");
            case "WRONG_VERSION" ->
                    db.update("UPDATE mom_mes.mes_qms_inspection_command SET command_version='V0'");
        }
        assertFalse(gate.sync("P1").getPassed());
        assertEquals("ERROR", value("gate_status", String.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED_WITH_DATA", "NULL", "THROWS", "DUPLICATE", "EXTRA", "MISSING"})
    void aFailedOrAmbiguousResponseCannotSupplyAnAcceptedTask(String kind) {
        switch (kind) {
            case "FAILED_WITH_DATA" -> responseCode = 1;
            case "NULL" -> nullResponse = true;
            case "THROWS" -> remoteThrows = true;
            case "DUPLICATE" -> duplicate = true;
            case "EXTRA" -> extra = true;
            case "MISSING" -> sources.clear();
        }
        assertFalse(gate.sync("P1").getPassed());
        assertEquals("ERROR", value("gate_status", String.class));
    }

    @Test
    void aMissingRemoteTaskActuallyClearsAllPreviousCachedReleaseNumbers() {
        gate.sync("P1");
        sources.clear();
        assertFalse(gate.sync("P1").getPassed());
        assertNull(value("inspection_result", String.class));
        assertNull(value("qms_usable_num", Integer.class));
        assertNull(value("qms_handle_num", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELED", "UNKNOWN", ""})
    void aCanceledUnknownOrUnjudgedSourceCannotCarryReleaseCapacity(String result) {
        sources.get(700L).setResult(result);
        var response = gate.sync("P1");
        assertFalse(response.getPassed());
        assertNull(response.getItems().get(0).getQmsUsableNum());
        assertNull(value("qms_usable_num", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"NEGATIVE", "EXCEEDS", "PARTIAL_QUALIFIED", "PARTIAL_RELEASE", "BAD_TOTAL"})
    void aMalformedResultCannotBecomeAWholeBatchRelease(String kind) {
        var q = sources.get(700L);
        switch (kind) {
            case "NEGATIVE" -> q.setUsableNum(-1);
            case "EXCEEDS" -> q.setHandleNum(6);
            case "PARTIAL_QUALIFIED" -> q.setUsableNum(2).setHandleNum(2);
            case "PARTIAL_RELEASE" -> q.setResult("RELEASE").setUsableNum(2).setHandleNum(2);
            case "BAD_TOTAL" -> q.setNum(0);
        }
        assertFalse(gate.sync("P1").getPassed());
        assertEquals("ERROR", value("gate_status", String.class));
    }

    @Test
    void aRealPartialDispositionKeepsItsExplicitCapacityWithoutPassingTheBatch() {
        sources.get(700L)
                .setResult("UN_QUALIFIED")
                .setStatus("UNHANDLED")
                .setUsableNum(2)
                .setHandleNum(3);
        var r = gate.sync("P1");
        assertFalse(r.getPassed());
        assertEquals(2, r.getItems().get(0).getQmsUsableNum());
        assertEquals(3, value("qms_handle_num", Integer.class));
    }

    @Test
    void warningPolicyCannotTurnAnUnverifiedSourceIntoAPassedJudgment() {
        rule.setGateMode("WARN");
        sources.get(700L).setFactoryCode("F2");
        var r = gate.sync("P1");
        assertTrue(r.getAllowed());
        assertFalse(r.getPassed());
        assertNull(r.getItems().get(0).getQmsUsableNum());
    }

    @Test
    void aLostSecondRecordReceiptRollsBackTheFirstAndCannotReturnSuccess() {
        seed(11, 701, "MIDDLE");
        rule.setInspectionTypes("FIRST,MIDDLE");
        fault.failAt = 2;
        assertThrows(RuntimeException.class, () -> gate.sync("P1"));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_mes.mes_inspection_gen_record WHERE"
                                + " gate_status='PENDING'",
                        Integer.class));
    }

    @Test
    void aLateRemoteSnapshotCannotOverwriteANewerSavedJudgment() throws Exception {
        firstRead = new CountDownLatch(1);
        releaseFirst = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor(r -> new Thread(r, "late-source"));
        try {
            var old =
                    pool.submit(
                            () -> {
                                TenantContextHolder.setTenantId(1L);
                                FactoryContextHolder.setFactoryCode("F1");
                                try {
                                    return gate.sync("P1");
                                } finally {
                                    TenantContextHolder.clear();
                                    FactoryContextHolder.clear();
                                }
                            });
            assertTrue(firstRead.await(5, TimeUnit.SECONDS));
            sources.get(700L)
                    .setResult("UN_QUALIFIED")
                    .setStatus("UNHANDLED")
                    .setUsableNum(0)
                    .setHandleNum(0);
            assertFalse(gate.sync("P1").getPassed());
            releaseFirst.countDown();
            assertThrows(ExecutionException.class, () -> old.get(5, TimeUnit.SECONDS));
            assertEquals("FAILED", value("gate_status", String.class));
            assertEquals("UN_QUALIFIED", value("inspection_result", String.class));
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TENANT", "FACTORY", "TENANT_SKIP", "FACTORY_SKIP"})
    void noSynchronizationCanBypassTheActualOrganization(String change) {
        switch (change) {
            case "TENANT" -> TenantContextHolder.clear();
            case "FACTORY" -> FactoryContextHolder.clear();
            case "TENANT_SKIP" -> TenantContextHolder.setTenantSkip();
            case "FACTORY_SKIP" -> FactoryContextHolder.setFactorySkip();
        }
        assertThrows(RuntimeException.class, () -> gate.sync("P1"));
        assertEquals("PENDING", value("gate_status", String.class));
    }

    @Test
    void aFinishCallerCannotDisableTheActualInspectionByPassingAForgedObject() {
        sources.get(700L).setFactoryCode("F2");
        var forged =
                new MesProduceTaskEntity().setCode("P1").setTaskCode("T1").setIsInspection("0");
        var r = gate.checkFinish(forged, new MesTaskEntity().setCode("T1"));
        assertFalse(r.getPassed());
    }

    @Test
    void theCanonicalGenerationTableCannotBeShadowedByAnEmptyPublicTable() {
        db.execute("DROP SYNONYM public.mes_inspection_gen_record");
        db.execute(
                "CREATE TABLE public.mes_inspection_gen_record AS SELECT * FROM"
                        + " mom_mes.mes_inspection_gen_record WHERE 1=0");
        assertTrue(gate.sync("P1").getPassed());
        assertEquals("PASSED", value("gate_status", String.class));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "QUALIFIED_ZERO",
                "QUALIFIED_NULL",
                "RELEASE",
                "RECEIVE",
                "EXEMPTION",
                "APPENDED"
            })
    void legitimateCompleteAndLegacySourcesRetainTheirExistingGateSemantics(String scenario) {
        var q = sources.get(700L);
        switch (scenario) {
            case "QUALIFIED_ZERO" -> q.setUsableNum(0).setHandleNum(0);
            case "QUALIFIED_NULL" -> q.setUsableNum(null).setHandleNum(null);
            case "APPENDED" -> q.setNum(7).setUsableNum(7).setHandleNum(7);
            default -> q.setResult(scenario);
        }
        assertTrue(gate.sync("P1").getPassed());
        assertEquals("PASSED", value("gate_status", String.class));
    }

    @Test
    void relabelingBothLiveCopiesCannotChangeTheFrozenCreationPhase() {
        rule.setInspectionTypes("LAST");
        db.update("UPDATE mom_mes.mes_inspection_gen_record SET inspection_type='LAST'");
        sources.get(700L).setLabelType("LAST");
        assertFalse(gate.sync("P1").getPassed());
        assertEquals("ERROR", value("gate_status", String.class));
    }

    @Test
    void aReplacedRootWithTheSameCodeCannotUseThePreviousOwnersInspection() {
        db.update("UPDATE mom_mes.mes_task SET id=101");
        assertFalse(gate.sync("P1").getPassed());
    }

    @Test
    void anUnreadyCreationLedgerCannotBeReplacedWithTheCurrentQmsTask() {
        db.execute("DROP ALIAS mom_mes.mom_qms_inspection_command_ready_v1");
        db.execute(
                "CREATE ALIAS mom_mes.mom_qms_inspection_command_ready_v1 AS 'Boolean"
                        + " ready(){return false;}'");
        assertFalse(gate.sync("P1").getPassed());
    }

    @Test
    void anInsertedRecordFailureCannotLeaveARemoteInspectionBehind() {
        fault.hits = 0;
        fault.failAt = 1;
        assertThrows(
                RuntimeException.class,
                () ->
                        gate.createChangePointFirstInspection(
                                "P1", "MES_QMS_V2:CHANGE:1", "R1", "change"));
        assertTrue(created.isEmpty());
        assertFalse(sources.containsKey(800L));
    }

    @Test
    void anUnknownCreationReceiptRecoversTheSameRemoteTaskAfterLocalRollback() {
        fault.hits = 0;
        fault.failAt = 2;
        assertThrows(
                RuntimeException.class,
                () ->
                        gate.createChangePointFirstInspection(
                                "P1", "MES_QMS_V2:CHANGE:1", "R1", "change"));
        assertEquals(List.of("MES_QMS_V2:CHANGE:1"), created);
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_mes.mes_inspection_gen_record", Integer.class));
        assertEquals(
                800L,
                db.queryForObject(
                        "SELECT qms_task_id FROM mom_mes.mes_qms_inspection_command WHERE id=900",
                        Long.class));
        fault.failAt = 0;
        var r =
                assertDoesNotThrow(
                        () ->
                                gate.createChangePointFirstInspection(
                                        "P1", "MES_QMS_V2:CHANGE:1", "R1", "change"));
        assertFalse(r.getPassed());
        assertEquals(800L, r.getItems().get(0).getInspectionId());
        assertEquals("PENDING", r.getItems().get(0).getGateStatus());
        assertEquals(1, created.size());
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_mes.mes_inspection_gen_record", Integer.class));
    }

    @Test
    void aLostBlockCounterRollsBackTheResultRatherThanPretendingItWasRecorded() {
        sources.get(700L)
                .setResult("UN_QUALIFIED")
                .setStatus("UNHANDLED")
                .setUsableNum(0)
                .setHandleNum(0);
        fault.hits = 0;
        fault.failAt = 2;
        assertThrows(RuntimeException.class, () -> gate.checkReport("P1"));
        assertEquals("PENDING", value("gate_status", String.class));
        assertEquals(0, value("blocked_count", Integer.class));
    }

    @Test
    void changedCachedEvidenceIsProtectedEvenWhenTheTimestampHasNotAdvanced() throws Exception {
        gate.sync("P1");
        LocalDateTime observed = value("last_sync_time", LocalDateTime.class);
        firstRead = new CountDownLatch(1);
        releaseFirst = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor(r -> new Thread(r, "late-source"));
        try {
            var old =
                    pool.submit(
                            () -> {
                                TenantContextHolder.setTenantId(1L);
                                FactoryContextHolder.setFactoryCode("F1");
                                try {
                                    return gate.sync("P1");
                                } finally {
                                    TenantContextHolder.clear();
                                    FactoryContextHolder.clear();
                                }
                            });
            assertTrue(firstRead.await(5, TimeUnit.SECONDS));
            sources.get(700L)
                    .setResult("UN_QUALIFIED")
                    .setStatus("UNHANDLED")
                    .setUsableNum(0)
                    .setHandleNum(0);
            assertFalse(gate.sync("P1").getPassed());
            // 模拟时间精度或旧写口未推进时间，仍必须以已保存的依据拒绝覆盖。
            db.update(
                    "UPDATE mom_mes.mes_inspection_gen_record SET last_sync_time=? WHERE id=10",
                    observed);
            releaseFirst.countDown();
            assertThrows(ExecutionException.class, () -> old.get(5, TimeUnit.SECONDS));
            assertEquals("FAILED", value("gate_status", String.class));
            assertEquals("UN_QUALIFIED", value("inspection_result", String.class));
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "TENANT",
                "FACTORY",
                "MISSING",
                "STOPPED",
                "BLANK_MODE",
                "BLANK_PHASE",
                "UNKNOWN_PHASE"
            })
    void anUnverifiableRuleCannotSilentlyDisableAnInspectionRequiredByTheProcess(String bad) {
        switch (bad) {
            case "TENANT" -> rule.setTenantId(2L);
            case "FACTORY" -> rule.setFactoryCode("F2");
            case "MISSING" -> rule = null;
            case "STOPPED" -> rule.setStatus("0");
            case "BLANK_MODE" -> rule.setGateMode(null);
            case "BLANK_PHASE" -> rule.setInspectionTypes(null);
            case "UNKNOWN_PHASE" -> rule.setInspectionTypes("OTHER");
        }
        assertThrows(RuntimeException.class, () -> gate.sync("P1"));
        assertEquals("PENDING", value("gate_status", String.class));
    }

    @Test
    void anExplicitNonePolicyAllowsOperationsWithoutInventingAPassedInspection() {
        rule.setGateMode("NONE");
        sources.get(700L).setFactoryCode("F2");
        var r = gate.sync("P1");
        assertTrue(r.getAllowed());
        assertFalse(r.getPassed());
        assertTrue(r.getItems().isEmpty());
    }

    @Test
    void aReportStageWithoutRequiredInspectionTypesDoesNotInventReleaseEvidence() {
        rule.setInspectionTypes("LAST");
        var r = gate.checkReport("P1");
        assertTrue(r.getAllowed());
        assertFalse(r.getPassed());
        assertTrue(
                r.getItems().stream()
                        .anyMatch(
                                i ->
                                        "LAST".equals(i.getInspectionType())
                                                && i.getInspectionId() == null));
    }

    @Test
    void anAllPhaseRefreshCannotPassAMissingRequiredLastInspection() {
        rule.setInspectionTypes("LAST");
        var r = gate.sync("P1");
        assertFalse(r.getAllowed());
        assertFalse(r.getPassed());
    }

    @Test
    void aMissingFirstInspectionRemainsAnExplicitObstacleToOtherReleaseCapacity() {
        phase("MIDDLE");
        rule.setInspectionTypes("FIRST,MIDDLE");
        sources.get(700L)
                .setResult("UN_QUALIFIED")
                .setStatus("UNHANDLED")
                .setUsableNum(2)
                .setHandleNum(3);
        var r = gate.sync("P1");
        assertFalse(r.getPassed());
        assertFalse(r.getAllowed());
        var missing =
                r.getItems().stream()
                        .filter(i -> "FIRST".equals(i.getInspectionType()))
                        .findFirst();
        assertTrue(missing.isPresent(), "缺少必需阶段必须在消费依据中明确表示，不能只在文案提示");
        assertNull(missing.get().getInspectionId());
        assertEquals("0", missing.get().getGenFlag());
        assertNull(missing.get().getQmsUsableNum());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_mes.mes_inspection_gen_record", Integer.class));
    }

    @Test
    void aPassedMandatoryInspectionRemainsRealEvidenceWhenOrdinaryGatingIsNone() {
        phase("MIDDLE");
        rule.setGateMode("NONE");
        db.update(
                "INSERT INTO"
                    + " mom_mes.mes_change_point_handle(id,tenant_id,factory_code,produce_task_code,event_code,status,intensified_inspection_record_id)"
                    + " VALUES(1,1,'F1','P1','EV1','M',10)");
        var r = gate.checkReport("P1");
        assertTrue(r.getAllowed());
        assertTrue(r.getPassed());
        assertEquals("BLOCK", r.getGateMode());
        assertEquals(700L, r.getItems().get(0).getInspectionId());
    }

    @Test
    void anUncreatedMandatoryInspectionCannotBeSkippedByManualRefresh() {
        db.update(
                "INSERT INTO"
                    + " mom_mes.mes_change_point_handle(id,tenant_id,factory_code,produce_task_code,event_code,status,intensified_inspection_next_time)"
                    + " VALUES(1,1,'F1','P1','EV1','M',?)",
                LocalDateTime.now().minusMinutes(1));
        var r = gate.sync("P1");
        assertFalse(r.getAllowed());
        assertFalse(r.getPassed());
        assertTrue(
                r.getItems().stream()
                        .anyMatch(i -> i.getInspectionId() == null && "0".equals(i.getGenFlag())));
    }

    @Test
    void ordinaryNoneStillIncludesTheExplicitObstacleForADueMandatoryInspection() {
        rule.setGateMode("NONE");
        db.update(
                "INSERT INTO"
                    + " mom_mes.mes_change_point_handle(id,tenant_id,factory_code,produce_task_code,event_code,status)"
                    + " VALUES(1,1,'F1','P1','EV1','M')");
        var r = gate.checkReport("P1");
        assertFalse(r.getAllowed());
        assertFalse(r.getPassed());
        assertTrue(
                r.getItems().stream()
                        .anyMatch(i -> i.getInspectionId() == null && "0".equals(i.getGenFlag())));
    }

    @Test
    void aPassedIntensifiedInspectionCannotStandInForTheRequiredLastInspection() throws Exception {
        phase("MIDDLE");
        rule.setInspectionTypes("LAST");
        db.update(
                "INSERT INTO"
                    + " mom_mes.mes_change_point_handle(id,tenant_id,factory_code,produce_task_code,event_code,status,intensified_inspection_record_id)"
                    + " VALUES(1,1,'F1','P1','EV1','M',10)");
        var decision = gate.checkReport("P1");
        assertTrue(decision.getAllowed());
        var quantities = new MesQualityQuantityTransactionTest();
        quantities.setup();
        try {
            quantities.assertBlockedDecisionKeepsHistoricalOutputPending(decision);
        } finally {
            quantities.clear();
        }
        var reporting = new MesReportParentStateTest();
        reporting.setup();
        try {
            reporting.assertUnjudgedDecisionCreatesPendingOutput(decision);
        } finally {
            reporting.clear();
        }
        assertFalse(decision.getPassed());
    }

    @Test
    void anOrdinaryGenerationRetryCarriesTheSameMandatoryObstacleAsARefresh() throws Exception {
        gate.createChangePointFirstInspection("P1", "MES_QMS_V2:CHANGE:1", "R1", "change");
        Long id =
                db.queryForObject(
                        "SELECT id FROM mom_mes.mes_inspection_gen_record WHERE"
                                + " source_request_id='MES_QMS_V2:CHANGE:1'",
                        Long.class);
        sources.get(800L)
                .setResult("QUALIFIED")
                .setStatus("FINISHED")
                .setUsableNum(5)
                .setHandleNum(5);
        db.update(
                "UPDATE mom_mes.mes_inspection_gen_record SET gen_flag='0',inspection_id=NULL WHERE"
                        + " id=?",
                id);
        db.update(
                "INSERT INTO"
                    + " mom_mes.mes_change_point_handle(id,tenant_id,factory_code,produce_task_code,event_code,status)"
                    + " VALUES(1,1,'F1','P1','EV1','M')");
        var decision = gate.retryGeneration(id);
        var quantities = new MesQualityQuantityTransactionTest();
        quantities.setup();
        try {
            quantities.assertBlockedDecisionKeepsHistoricalOutputPending(decision);
        } finally {
            quantities.clear();
        }
        assertFalse(decision.getAllowed());
        assertFalse(decision.getPassed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FIRST", "MIDDLE"})
    void aFailedChangePointCreationRetriesItsOriginalInspectionPhase(String phase) {
        currentStandard = null;
        if (phase.equals("FIRST"))
            gate.createChangePointFirstInspection("P1", "MES_QMS_V2:POINT:1", "R1", "change");
        else
            gate.createChangePointIntensifiedInspection("P1", "MES_QMS_V2:POINT:1", "R1", "change");
        Long id =
                db.queryForObject(
                        "SELECT id FROM mom_mes.mes_inspection_gen_record WHERE"
                                + " source_request_id='MES_QMS_V2:POINT:1'",
                        Long.class);
        currentStandard = new com.kitex.mom.qms.entity.QmsQualityStandardEntity();
        currentStandard.setId(9L);
        currentStandard.setInspectionMethod("ALL");
        var r = gate.retryChangePointInspection(id);
        assertEquals("PENDING", r.getItems().get(0).getGateStatus());
        assertEquals(phase, sources.get(800L).getLabelType());
        sources.get(800L)
                .setResult("QUALIFIED")
                .setStatus("FINISHED")
                .setUsableNum(5)
                .setHandleNum(5);
        assertTrue(gate.syncChangePointInspection(id).getPassed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"QMS_CONFIRMED", "OUTBOUND_STARTED"})
    void aLostReceiptReplaysTheOriginalBodyAcrossShiftAndStandardChanges(String state) {
        fault.hits = 0;
        fault.failAt = 2;
        assertThrows(
                RuntimeException.class,
                () ->
                        gate.createChangePointFirstInspection(
                                "P1", "MES_QMS_V2:POINT:1", "R1", "change"));
        String original =
                db.queryForObject(
                        "SELECT command_snapshot FROM mom_mes.mes_qms_inspection_command WHERE"
                                + " id=900",
                        String.class);
        if (state.equals("OUTBOUND_STARTED"))
            db.update(
                    "UPDATE mom_mes.mes_qms_inspection_command SET"
                            + " status='OUTBOUND_STARTED',qms_task_id=NULL WHERE id=900");
        currentShift = "NIGHT";
        currentStandard.setId(10L);
        fault.failAt = 0;
        var restored =
                gate.createChangePointFirstInspection("P1", "MES_QMS_V2:POINT:1", "R1", "change");
        assertEquals(800L, restored.getItems().get(0).getInspectionId());
        assertEquals("PENDING", restored.getItems().get(0).getGateStatus());
        assertEquals(
                original,
                db.queryForObject(
                        "SELECT command_snapshot FROM mom_mes.mes_qms_inspection_command WHERE"
                                + " id=900",
                        String.class));
        assertTrue(outboundSnapshots.stream().allMatch(original::equals));
        assertEquals(1, created.size());
        assertEquals(9L, sources.get(800L).getStandardId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FIRST", "MIDDLE"})
    void aCompleteV2ContractWithoutItsOptionalRootPrimaryKeyRemainsUsable(String phase) {
        phase(phase);
        String raw =
                db.queryForObject(
                        "SELECT command_snapshot FROM mom_mes.mes_qms_inspection_command WHERE"
                                + " id=10",
                        String.class);
        raw = raw.replace("orderId:3:100\n", "orderId:-1:\n");
        String hash = sha(raw);
        db.update(
                "UPDATE mom_mes.mes_qms_inspection_command SET"
                        + " command_snapshot=?,command_fingerprint=? WHERE id=10",
                raw,
                hash);
        sources.get(700L).setOrderId(null).setSourceRequestFingerprint(hash);
        assertTrue(gate.sync("P1").getPassed());
        assertNull(sources.get(700L).getOrderId());
        assertTrue(value("sync_message", String.class).contains("未冻结根工单ID"));
        assertTrue(
                db.queryForObject(
                                "SELECT command_snapshot FROM mom_mes.mes_qms_inspection_command"
                                        + " WHERE id=10",
                                String.class)
                        .contains("orderId:-1:\n"));
    }

    @Test
    void aRealFirstInspectionCreatorContractAndItsReceiptRemainRecoverableWithoutARootId()
            throws Exception {
        var creator = MesTaskServiceImpl.class.getDeclaredConstructors()[0];
        Object target =
                creator.newInstance(
                        Arrays.stream(creator.getParameterTypes())
                                .map(type -> mock(type))
                                .toArray());
        var process =
                new MesProduceTaskEntity()
                        .setCode("P1")
                        .setTaskCode("T1")
                        .setMatCode("M1")
                        .setRouteCode("OP1")
                        .setNoticeNum(new BigDecimal("5"));
        var standard = new com.kitex.mom.qms.entity.QmsQualityStandardEntity();
        standard.setId(9L);
        standard.setInspectionMethod("ALL");
        InspectionProcedureCreateParam command =
                ReflectionTestUtils.invokeMethod(
                        target, "buildCreateParam", process, standard, "MES_QMS_V2:P1:FIRST:10");
        assertNotNull(command);
        assertNull(command.getOrderId());
        String raw = InspectionProcedureSourceContract.snapshot(command),
                hash = InspectionProcedureSourceContract.fingerprint(command);
        db.update(
                "UPDATE mom_mes.mes_qms_inspection_command SET"
                        + " command_snapshot=?,command_fingerprint=? WHERE id=10",
                raw,
                hash);
        var q = sources.get(700L);
        BeanUtils.copyProperties(command, q);
        q.setId(700L);
        q.setSourceRequestFingerprint(hash);
        assertTrue(gate.sync("P1").getPassed());
        db.update(
                "UPDATE mom_mes.mes_inspection_gen_record SET gen_flag='0',inspection_id=NULL WHERE"
                        + " id=10");
        assertTrue(gate.retryGeneration(10L).getPassed());
        assertEquals(
                700L,
                db.queryForObject(
                        "SELECT inspection_id FROM mom_mes.mes_inspection_gen_record WHERE id=10",
                        Long.class));
        assertEquals(
                raw,
                db.queryForObject(
                        "SELECT command_snapshot FROM mom_mes.mes_qms_inspection_command WHERE"
                                + " id=10",
                        String.class));
        assertTrue(created.isEmpty());
    }

    private String sha(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            text.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void phase(String p) {
        rule.setInspectionTypes(p);
        db.update("DELETE FROM mom_mes.mes_inspection_gen_record");
        db.update("DELETE FROM mom_mes.mes_qms_inspection_command");
        sources.clear();
        seed(10, 700, p);
    }

    private <T> T value(String field, Class<T> type) {
        return db.queryForObject(
                "SELECT " + field + " FROM mom_mes.mes_inspection_gen_record WHERE id=10", type);
    }

    private static <T> T bridge(Class<T> type, Object target) {
        return type.cast(
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (p, m, a) -> {
                            try {
                                return target.getClass()
                                        .getMethod(m.getName(), m.getParameterTypes())
                                        .invoke(target, a);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        }));
    }

    static class Roots extends ServiceImpl<MesTaskMapper, MesTaskEntity> {}

    static class Produces extends ServiceImpl<MesProduceTaskMapper, MesProduceTaskEntity> {}

    private void table(MybatisConfiguration config, Class<?> type) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(config, "gate-source-ddl"), type);
        var info = TableInfoHelper.getTableInfo(type);
        String canonical =
                info.getTableName().contains(".")
                        ? info.getTableName()
                        : "mom_mes." + info.getTableName();
        var sql =
                new StringBuilder("CREATE TABLE ")
                        .append(canonical)
                        .append("(id bigint primary key");
        for (var f : info.getFieldList()) {
            Class<?> t = f.getPropertyType();
            String st =
                    t == Long.class
                            ? "bigint"
                            : t == Integer.class
                                    ? "integer"
                                    : t == Boolean.class
                                            ? "boolean"
                                            : t == BigDecimal.class
                                                    ? "decimal(24,8)"
                                                    : t == LocalDateTime.class || t == Date.class
                                                            ? "timestamp"
                                                            : "varchar(262144)";
            sql.append(',').append(f.getColumn()).append(' ').append(st);
            if (f.getColumn().equals("del_flag")) sql.append(" DEFAULT '0'");
        }
        db.execute(sql.append(')').toString());
        db.execute(
                "CREATE SYNONYM public."
                        + canonical.substring("mom_mes.".length())
                        + " FOR "
                        + canonical);
    }

    @Intercepts(
            @Signature(
                    type = Executor.class,
                    method = "update",
                    args = {MappedStatement.class, Object.class}))
    static class WriteFault implements Interceptor {
        int failAt, hits;

        @Override
        public Object intercept(Invocation i) throws Throwable {
            var m = (MappedStatement) i.getArgs()[0];
            if (m.getId().startsWith(MesInspectionGenRecordMapper.class.getName() + ".")
                    && ++hits == failAt) return 0;
            return i.proceed();
        }
    }
}
