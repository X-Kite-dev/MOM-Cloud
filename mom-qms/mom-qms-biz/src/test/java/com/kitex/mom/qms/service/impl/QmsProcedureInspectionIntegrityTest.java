package com.kitex.mom.qms.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.qms.dto.inspection.*;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.*;
import com.kitex.mom.qms.service.*;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real task/detail/result/history/event/outbox transactions; factory and external downstream ports
 * are adapted.
 */
class QmsProcedureInspectionIntegrityTest {
    private QmsInspectionHistoryTransactionTest historyFixture;
    private JdbcTemplate db;
    private SqlSessionTemplate sessions;
    private InspectionProcedureTaskService service;
    private QualityStandardService standards;
    private QualityStandardItemService items;
    private QualitySamplingStrategiesService sampling;
    private QmsQualityStandardEntity standard;
    private QmsQualityStandardItemEntity item;
    private QmsReworkResultPublisher rework;
    private QmsPlanEventOutboxService plan;
    private QmsBadInfoService bad;
    private boolean authorized = true;
    private final WriteFault fault = new WriteFault();

    @BeforeEach
    void setup() throws Exception {
        historyFixture = new QmsInspectionHistoryTransactionTest();
        historyFixture.setup();
        db = (JdbcTemplate) ReflectionTestUtils.getField(historyFixture, "db");
        sessions = (SqlSessionTemplate) ReflectionTestUtils.getField(historyFixture, "sessions");
        var config = (MybatisConfiguration) sessions.getConfiguration();
        for (var interceptor : config.getInterceptors())
            if (interceptor instanceof MybatisPlusInterceptor mp)
                for (var inner : mp.getInterceptors())
                    if (inner instanceof TenantLineInnerInterceptor tenant)
                        tenant.setTenantLineHandler(
                                new TenantLineHandler() {
                                    public net.sf.jsqlparser.expression.Expression getTenantId() {
                                        return new net.sf.jsqlparser.expression.LongValue(
                                                TenantContextHolder.getTenantId());
                                    }

                                    public boolean ignoreTable(String table) {
                                        return table.toLowerCase(Locale.ROOT)
                                                .endsWith(
                                                        "qms_inspection_procedure_item_result_record");
                                    }
                                });
        for (var type :
                List.of(
                        QmsInspectionProcedureTaskDetailEntity.class,
                        QmsInspectionProcedureItemResultRecordEntity.class,
                        QmsInspectionEventOutboxEntity.class,
                        QmsInspectionBasisEntity.class)) table(config, type);
        for (var mapper :
                List.of(
                        QmsInspectionProcedureTaskDetailMapper.class,
                        QmsInspectionProcedureItemResultRecordMapper.class,
                        QmsInspectionEventOutboxMapper.class,
                        QmsInspectionBasisMapper.class)) config.addMapper(mapper);
        config.addInterceptor(fault);
        db.execute(
                "CREATE ALIAS mom_qms.mom_inspection_event_outbox_ready_v1 FOR"
                    + " 'com.kitex.mom.qms.service.impl.QmsInspectionHistoryTransactionTest.installedVersion'");
        @SuppressWarnings("unchecked")
        var deps =
                (Map<Class<?>, Object>)
                        ReflectionTestUtils.getField(historyFixture, "dependencies");
        ReflectionTestUtils.setField(
                deps.get(QmsInspectionEventPublisher.class),
                "outbox",
                sessions.getMapper(QmsInspectionEventOutboxMapper.class));
        db.update("DELETE FROM mom_qms.qms_inspection_procedure_history");
        db.update(
                "UPDATE mom_qms.qms_inspection_procedure_task SET"
                    + " num=2,inspection_num=2,status='WAIT',result=NULL,usable_num=0,handle_num=0,standard_id=1"
                    + " WHERE id=700");
        db.execute(
                "CREATE ALIAS mom_qms.mom_inspection_basis_ready_v1 FOR"
                    + " 'com.kitex.mom.qms.service.impl.QmsInspectionHistoryTransactionTest.installedVersion'");
        standards = mock(QualityStandardService.class);
        items = mock(QualityStandardItemService.class);
        sampling = mock(QualitySamplingStrategiesService.class);
        standard =
                new QmsQualityStandardEntity()
                        .setFactoryCode("F1")
                        .setMatCode("MAT")
                        .setInspectionType("PROCEDURE")
                        .setPublished(1)
                        .setInspectionMethod("FULL")
                        .setNum(2);
        standard.setId(1L);
        standard.setTenantId(1L);
        item =
                new QmsQualityStandardItemEntity()
                        .setFactoryCode("F1")
                        .setStandardId(1L)
                        .setName("绝缘检查")
                        .setCheckType("RANG")
                        .setLowerLimit(0D)
                        .setUpperLimit(10D)
                        .setSamplingStrategiesId("901");
        item.setId(501L);
        item.setTenantId(1L);
        when(standards.getById(any())).thenAnswer(c -> standard);
        when(standards.getOne(any())).thenAnswer(c -> standard);
        when(items.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenAnswer(c -> List.of(item));
        when(items.judgementCheckResult(any(), any()))
                .thenAnswer(
                        c ->
                                new QualityStandardItemServiceImpl()
                                        .judgementCheckResult(c.getArgument(0), c.getArgument(1)));
        var strategy =
                new QmsQualitySamplingStrategiesEntity()
                        .setFactoryCode("F1")
                        .setFrequency(List.of(new Frequency().setS(2).setN(0).setAc(0).setRe(1)));
        strategy.setId(901L);
        strategy.setTenantId(1L);
        when(sampling.getById(any())).thenReturn(strategy);
        when(sampling.getOne(any())).thenReturn(strategy);
        rework = mock(QmsReworkResultPublisher.class);
        plan = mock(QmsPlanEventOutboxService.class);
        bad = mock(QmsBadInfoService.class);
        var factories = mock(RemoteMdmFactoryAccessService.class);
        when(factories.check(any())).thenAnswer(c -> R.ok(authorized));
        var details = new QmsInspectionProcedureTaskDetailServiceImpl();
        ReflectionTestUtils.setField(
                details,
                "baseMapper",
                sessions.getMapper(QmsInspectionProcedureTaskDetailMapper.class));
        var records = new InspectionProcedureItemResultRecordServiceImpl();
        ReflectionTestUtils.setField(
                records,
                "baseMapper",
                sessions.getMapper(QmsInspectionProcedureItemResultRecordMapper.class));
        var dependencies = new HashMap<Class<?>, Object>();
        dependencies.put(QmsInspectionProcedureTaskDetailService.class, details);
        dependencies.put(InspectionProcedureItemResultRecordService.class, records);
        dependencies.put(QualityStandardService.class, standards);
        dependencies.put(QualityStandardItemService.class, items);
        dependencies.put(QualitySamplingStrategiesService.class, sampling);
        dependencies.put(
                InspectionProcedureHistoryService.class,
                ReflectionTestUtils.getField(historyFixture, "procedure"));
        dependencies.put(QmsReworkResultPublisher.class, rework);
        dependencies.put(QmsPlanEventOutboxService.class, plan);
        dependencies.put(QmsBadInfoService.class, bad);
        dependencies.put(
                QmsInspectionHistorySupport.class, new QmsInspectionHistorySupport(factories));
        var planFactory =
                new QmsInspectionPlanFactory(
                        standards,
                        items,
                        sampling,
                        (QmsInspectionHistorySupport)
                                dependencies.get(QmsInspectionHistorySupport.class));
        var planProxy = new ProxyFactory(planFactory);
        planProxy.setProxyTargetClass(true);
        planProxy.addAdvice(
                new TransactionInterceptor(
                        (org.springframework.transaction.PlatformTransactionManager)
                                ReflectionTestUtils.getField(historyFixture, "transactions"),
                        new AnnotationTransactionAttributeSource()));
        var production = mock(QmsInspectionProductionBasisService.class);
        when(production.freeze(any()))
                .thenReturn(null); // Other tests isolate the remote production-evidence boundary.
        dependencies.put(
                QmsInspectionBasisService.class,
                new QmsInspectionBasisService(
                        sessions.getMapper(QmsInspectionBasisMapper.class),
                        (QmsInspectionPlanFactory) planProxy.getProxy(),
                        (QmsInspectionHistorySupport)
                                dependencies.get(QmsInspectionHistorySupport.class),
                        new com.kitex.common.security.component.PermissionService(),
                        production));
        var constructor = InspectionProcedureTaskServiceImpl.class.getConstructors()[0];
        var target =
                constructor.newInstance(
                        Arrays.stream(constructor.getParameterTypes())
                                .map(t -> dependencies.computeIfAbsent(t, k -> mock(k)))
                                .toArray());
        ReflectionTestUtils.setField(
                target, "baseMapper", sessions.getMapper(QmsInspectionProcedureTaskMapper.class));
        var proxy = new ProxyFactory(target);
        proxy.setInterfaces(InspectionProcedureTaskService.class);
        proxy.addAdvice(
                new TransactionInterceptor(
                        (org.springframework.transaction.PlatformTransactionManager)
                                ReflectionTestUtils.getField(historyFixture, "transactions"),
                        new AnnotationTransactionAttributeSource()));
        service = (InspectionProcedureTaskService) proxy.getProxy();
    }

    @AfterEach
    void close() {
        if (historyFixture != null) historyFixture.clear();
    }

    @Test
    void emptyInspectionItemsCannotGenerateAUsableTask() {
        when(items.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of());
        assertThrows(RuntimeException.class, () -> service.create(command()));
        assertEquals(1, count("qms_inspection_procedure_task"));
        assertEquals(0, count("qms_inspection_procedure_history"));
    }

    @Test
    void randomZeroMeansFullInspectionForBothHeaderAndDetails() {
        standard.setInspectionMethod("RANDOM");
        long id = service.create(command());
        assertEquals(2, value(id, "inspection_num"));
        assertEquals(2, detailValue(id, "total_num"));
        assertEquals(2, detailValue(id, "wait_num"));
    }

    @Test
    void fixedSamplesAboveLotSizeUseTheLotSizeEverywhere() {
        standard.setInspectionMethod("FIXED").setNum(20);
        long id = service.create(command());
        assertEquals(2, value(id, "inspection_num"));
        assertEquals(2, detailValue(id, "total_num"));
    }

    @ParameterizedTest
    @CsvSource({"FULL,20,8,2", "FIXED,1,2,1", "PENDING,20,1,1", "PENDING,20,8,2"})
    void generatedSamplesAgreeForSupportedSamplingMethods(
            String method, int fixed, int requested, int expected) {
        standard.setInspectionMethod(method).setNum(fixed);
        var c = command().setInspectionNum(requested);
        c.setSourceRequestFingerprint(InspectionProcedureSourceContract.fingerprint(c));
        long id = service.create(c);
        assertEquals(expected, value(id, "inspection_num"));
        assertEquals(expected, detailValue(id, "total_num"));
        assertEquals(expected, detailValue(id, "wait_num"));
    }

    @ParameterizedTest
    @CsvSource({"FIXED,0,2", "FIXED,-1,2", "PENDING,2,0", "PENDING,2,-1", "UNKNOWN,2,2"})
    void invalidSamplingConfigurationRollsBackTheGeneratedTask(
            String method, int fixed, int requested) {
        standard.setInspectionMethod(method).setNum(fixed);
        var c = command().setInspectionNum(requested);
        c.setSourceRequestFingerprint(InspectionProcedureSourceContract.fingerprint(c));
        assertThrows(RuntimeException.class, () -> service.create(c));
        assertEquals(1, count("qms_inspection_procedure_task"));
        assertEquals(0, count("qms_inspection_procedure_task_detail"));
        assertEquals(0, count("qms_inspection_event"));
    }

    @Test
    void emptyActualItemsCannotBecomeAQualifiedConclusion() {
        assertThrows(RuntimeException.class, () -> service.submitInspectionResult(700L));
        assertNull(result(700));
        assertEquals(0, count("qms_inspection_procedure_history"));
    }

    @Test
    void completeCachedCountersWithoutActualSamplesCannotPass() {
        seedDetail(10, 700, 2, 2, 0, 0, true);
        assertThrows(RuntimeException.class, () -> service.submitInspectionResult(700L));
        assertNull(result(700));
    }

    @Test
    void frozenCriteriaAreUsedAfterTheLiveStandardChanges() {
        long id = service.create(command());
        standard.setInspectionMethod("FIXED");
        item.setUpperLimit(1D);
        service.temporarilySave(sample(detailId(id), 5));
        assertEquals(
                Boolean.TRUE,
                db.queryForObject(
                        "SELECT qualified FROM mom_qms.qms_inspection_procedure_item_result_record",
                        Boolean.class));
    }

    @Test
    void collectionDiscardsTheClientSuppliedRecordIdentity() {
        long id = service.create(command());
        var sample = sample(detailId(id), 5);
        sample.getRecord().setId(888L);
        service.temporarilySave(sample);
        assertNotEquals(
                888L,
                db.queryForObject(
                        "SELECT id FROM mom_qms.qms_inspection_procedure_item_result_record",
                        Long.class));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "QmsInspectionProcedureTaskMapper.insert",
                "QmsInspectionProcedureTaskMapper.updateById",
                "QmsInspectionProcedureTaskDetailMapper.insert"
            })
    void failedCreationAcknowledgementRollsBackParentChildrenAndOriginals(String operation) {
        fault.lose = operation;
        assertThrows(RuntimeException.class, () -> service.create(command()));
        assertEquals(1, count("qms_inspection_procedure_task"));
        assertEquals(0, count("qms_inspection_procedure_task_detail"));
        assertEquals(0, count("qms_inspection_event"));
        assertEquals(0, count("qms_inspection_event_outbox"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "QmsInspectionProcedureItemResultRecordMapper.insert",
                "QmsInspectionProcedureTaskDetailMapper.updateById",
                "QmsInspectionProcedureTaskMapper.updateById"
            })
    void failedCollectionAcknowledgementRollsBackTheSampleAndCounters(String operation) {
        long id = service.create(command());
        fault.lose = operation;
        assertThrows(
                RuntimeException.class, () -> service.temporarilySave(sample(detailId(id), 5)));
        assertEquals(0, count("qms_inspection_procedure_item_result_record"));
        assertEquals(0, detailValue(id, "finish_num"));
        assertEquals(
                "WAIT",
                db.queryForObject(
                        "SELECT status FROM mom_qms.qms_inspection_procedure_task WHERE id=?",
                        String.class,
                        id));
    }

    @Test
    void manualCompletionSetsTheSameBatchQuantitiesAndPublishesExactlyOnce() {
        long id = service.create(command());
        long d = detailId(id);
        service.temporarilySave(sample(d, 5));
        service.temporarilySave(sample(d, 6));
        service.submitInspectionResult(id);
        assertEquals("QUALIFIED", result(id));
        assertEquals(2, value(id, "usable_num"));
        assertEquals(2, value(id, "handle_num"));
        service.submitInspectionResult(id);
        verify(rework, times(1)).publish("PROCEDURE", id);
        assertEquals(2, count("qms_inspection_event"));
    }

    @Test
    void finalAutomaticBatchPublishesReinspectionAndUsesDurableSampleFacts() {
        long id = service.create(command());
        long d = detailId(id);
        service.temporarilySave(sample(d, 5));
        var batch = new InspectionCheckResultBatchParam();
        batch.setTaskId(id);
        batch.setResults(List.of(sample(d, 6)));
        service.batchTemporarilySave(batch);
        assertEquals("QUALIFIED", result(id));
        assertEquals(2, value(id, "usable_num"));
        verify(rework, times(1)).publish("PROCEDURE", id);
        assertEquals(2, count("qms_inspection_event_outbox"));
    }

    @Test
    void partialBatchPersistsSamplesWithoutPrematureConclusionOrDownstreamPublication() {
        long id = service.create(command()), d = detailId(id);
        service.batchTemporarilySave(batch(id, sample(d, 5)));
        assertNull(result(id));
        assertEquals(1, detailValue(id, "finish_num"));
        assertEquals(1, count("qms_inspection_event"));
        verifyZeroInteractions(rework, plan, bad);
        assertThrows(RuntimeException.class, () -> service.submitInspectionResult(id));
    }

    @Test
    void unqualifiedInspectionUsesTheOriginalDispositionAndIsolationPaths() {
        long id = service.create(command()), d = detailId(id);
        service.temporarilySave(sample(d, 20));
        service.batchTemporarilySave(batch(id, sample(d, 5)));
        assertEquals("UN_QUALIFIED", result(id));
        assertEquals(0, value(id, "usable_num"));
        assertEquals(0, value(id, "handle_num"));
        verify(bad)
                .add(
                        argThat(
                                b ->
                                        Objects.equals(b.getResourceTaskId(), id)
                                                && b.getNum().intValueExact() == 2));
        verify(plan).enqueueIsolation("PROCEDURE", id);
        verify(rework).publish("PROCEDURE", id);
        assertEquals(2, count("qms_inspection_event_outbox"));
    }

    @Test
    void failedIsolationRegistrationRollsBackTheLastSampleAndFinalization() {
        long id = service.create(command()), d = detailId(id);
        service.temporarilySave(sample(d, 20));
        doThrow(new IllegalStateException("PLAN registration failed"))
                .when(plan)
                .enqueueIsolation("PROCEDURE", id);
        assertThrows(
                RuntimeException.class,
                () -> service.batchTemporarilySave(batch(id, sample(d, 5))));
        assertNull(result(id));
        assertEquals(1, detailValue(id, "finish_num"));
        assertEquals(1, detailValue(id, "wait_num"));
        assertEquals(1, count("qms_inspection_procedure_item_result_record"));
        assertEquals(1, count("qms_inspection_event_outbox"));
        verifyZeroInteractions(rework);
    }

    @Test
    void duplicateItemsInABatchRollBackEarlierItemsFromThatRequest() {
        long id = service.create(command()), d = detailId(id);
        assertThrows(
                RuntimeException.class,
                () -> service.batchTemporarilySave(batch(id, sample(d, 5), sample(d, 6))));
        assertEquals(0, count("qms_inspection_procedure_item_result_record"));
        assertEquals(0, detailValue(id, "finish_num"));
    }

    @Test
    void changedHeaderCannotBeUsedToConcludeAnOtherwiseCompleteInspection() {
        long id = service.create(command()), d = detailId(id);
        service.temporarilySave(sample(d, 5));
        service.temporarilySave(sample(d, 6));
        db.update(
                "UPDATE mom_qms.qms_inspection_procedure_task SET inspection_num=1 WHERE id=?", id);
        assertThrows(RuntimeException.class, () -> service.submitInspectionResult(id));
        assertNull(result(id));
    }

    @Test
    void wrongFactoryOrTenantCannotCollectOrReadTheScopedSampleAggregate() {
        long id = service.create(command()), d = detailId(id);
        service.temporarilySave(sample(d, 5));
        FactoryContextHolder.setFactoryCode("F2");
        assertThrows(RuntimeException.class, () -> service.temporarilySave(sample(d, 5)));
        assertThrows(RuntimeException.class, () -> service.submitInspectionResult(id));
        assertTrue(
                sessions.getMapper(QmsInspectionProcedureTaskMapper.class)
                        .inspectionSamples(id, 1L, "F2")
                        .isEmpty());
        FactoryContextHolder.setFactoryCode("F1");
        TenantContextHolder.setTenantId(2L);
        assertThrows(RuntimeException.class, () -> service.submitInspectionResult(id));
        assertTrue(
                sessions.getMapper(QmsInspectionProcedureTaskMapper.class)
                        .inspectionSamples(id, 2L, "F1")
                        .isEmpty());
        TenantContextHolder.setTenantId(1L);
        assertEquals(1, count("qms_inspection_procedure_item_result_record"));
    }

    @Test
    void collectionEndpointsRequireTheSameSubmitPermissionAsFinalization() throws Exception {
        var controller = com.kitex.mom.qms.controller.InspectionProcedureTaskController.class;
        for (String method :
                List.of("temporarilySave", "batchTemporarilySave", "submitInspectionResult")) {
            var found =
                    Arrays.stream(controller.getDeclaredMethods())
                            .filter(m -> m.getName().equals(method))
                            .findFirst()
                            .orElseThrow();
            assertEquals(
                    "@pms.hasPermission('procedureTask:submit')",
                    found.getAnnotation(
                                    org.springframework.security.access.prepost.PreAuthorize.class)
                            .value());
        }
    }

    @Test
    void factoryWriteDenialPreventsCollection() {
        long id = service.create(command());
        authorized = false;
        assertThrows(
                RuntimeException.class, () -> service.temporarilySave(sample(detailId(id), 5)));
        assertEquals(0, count("qms_inspection_procedure_item_result_record"));
        assertEquals(0, fault.lockAttempts.get());
    }

    @Test
    void changedLiveAcceptanceThresholdsCannotRewriteAnInspectionWithFailedSamples() {
        standard.setInspectionMethod("RANDOM");
        var strategy =
                new QmsQualitySamplingStrategiesEntity()
                        .setFactoryCode("F1")
                        .setFrequency(List.of(new Frequency().setS(2).setN(2).setAc(0).setRe(1)));
        strategy.setId(901L);
        strategy.setTenantId(1L);
        when(sampling.getOne(any())).thenReturn(strategy);
        long id =
                service.create(
                        command()
                                .setSourceRequestId("LOCAL-DRIFT")
                                .setSourceRequestFingerprint(null)
                                .setNum(5));
        service.temporarilySave(sample(detailId(id), 20));
        strategy.getFrequency().get(0).setAc(1).setRe(2);
        // 模拟升级前未留存完整依据的任务；新任务的冻结方案另有真实事务覆盖。
        db.update(
                "DELETE FROM mom_qms.qms_inspection_basis WHERE inspection_type='PROCEDURE' AND"
                        + " task_id=?",
                id);
        assertThrows(
                RuntimeException.class,
                () -> service.append(new InspectionAppendParam().setId(id).setNum(1)));
        assertEquals(5, value(id, "num"));
        assertEquals(0, detailValue(id, "ac"));
        assertEquals(1, detailValue(id, "unqualified_num"));
        assertEquals(1, count("qms_inspection_event"));
        service.temporarilySave(sample(detailId(id), 5));
        service.submitInspectionResult(id);
        assertEquals("UN_QUALIFIED", result(id));
    }

    @ParameterizedTest
    @CsvSource({"POST,create", "POST,batchCreate", "PUT,update", "POST,append"})
    void manualMutationEndpointsDenyAnActorWithoutTheExistingInspectionAddAction(
            String method, String path) throws Exception {
        var mvc =
                (org.springframework.test.web.servlet.MockMvc)
                        ReflectionTestUtils.getField(historyFixture, "mvc");
        String body = "batchCreate".equals(path) ? "[]" : "{}";
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                                        org.springframework.http.HttpMethod.valueOf(method),
                                        "/inspection/procedure/task/" + path)
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isForbidden());
    }

    @Test
    void manualCreationCannotBypassFactoryWriteAuthorization() {
        authorized = false;
        var controller = controller();
        var local = command().setSourceRequestId("LOCAL-MANUAL").setSourceRequestFingerprint(null);
        assertThrows(RuntimeException.class, () -> controller.create(local));
        assertThrows(RuntimeException.class, () -> controller.batchCreate(List.of(local)));
        assertEquals(1, count("qms_inspection_procedure_task"));
        assertEquals(0, fault.lockAttempts.get());
    }

    @Test
    void automaticFeignCreationUsesSeparateTrustedRoutesWhileKeepingTheUserToken()
            throws Exception {
        var remote = com.kitex.mom.qms.api.feign.RemoteProcedureService.class;
        for (String method : List.of("create", "batchCreate")) {
            var found =
                    Arrays.stream(remote.getDeclaredMethods())
                            .filter(m -> m.getName().equals(method))
                            .findFirst()
                            .orElseThrow();
            assertEquals(
                    "/inspection/procedure/task/internal/" + method,
                    found.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class)
                            .value()[0]);
            assertNotNull(
                    found.getAnnotation(com.kitex.common.feign.annotation.InternalCall.class));
            assertNull(found.getAnnotation(com.kitex.common.feign.annotation.NoToken.class));
        }
    }

    @Test
    void trustedCreationRejectsAnExternalRequestButDoesNotRequireAnOperatorsQmsCreateGrant()
            throws Exception {
        authorized = false;
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var factory =
                new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(controller());
        factory.addAspect(new com.kitex.common.security.component.MomSecurityInnerAspect(request));
        Object proxy = factory.getProxy();
        var method =
                proxy.getClass()
                        .getMethod("createFromSource", InspectionProcedureCreateParam.class);
        var rejected =
                assertThrows(
                        java.lang.reflect.InvocationTargetException.class,
                        () -> method.invoke(proxy, command()));
        assertInstanceOf(
                org.springframework.security.access.AccessDeniedException.class,
                rejected.getCause());
        assertEquals(1, count("qms_inspection_procedure_task"));
        request.addHeader(
                com.kitex.common.core.constant.SecurityConstants.FROM,
                com.kitex.common.core.constant.SecurityConstants.FROM_IN);
        assertNotNull(((R<?>) method.invoke(proxy, command())).getData());
        assertEquals(2, count("qms_inspection_procedure_task"));
    }

    private com.kitex.mom.qms.controller.InspectionProcedureTaskController controller() {
        return new com.kitex.mom.qms.controller.InspectionProcedureTaskController(
                service,
                mock(InspectionProcedureHistoryService.class),
                mock(InspectionProcedureHandleRecordService.class),
                mock(QmsReworkOrderService.class),
                mock(QmsInspectionEventService.class));
    }

    @Test
    void manualCreationUsesTheSameTransactionAndCannotClaimAnInternalMesSource() {
        assertThrows(RuntimeException.class, () -> controller().create(command()));
        var first = command().setSourceRequestId("LOCAL-ONE").setSourceRequestFingerprint(null);
        var second =
                command()
                        .setTaskCode("PT2")
                        .setSourceTaskCode("PT2")
                        .setSourceRequestId("LOCAL-TWO")
                        .setSourceRequestFingerprint(null);
        when(items.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(item), List.of());
        assertThrows(
                RuntimeException.class, () -> controller().batchCreate(List.of(first, second)));
        assertEquals(1, count("qms_inspection_procedure_task"));
        assertEquals(0, count("qms_inspection_procedure_task_detail"));
        assertEquals(0, count("qms_inspection_event_outbox"));
        when(items.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(item));
        assertNotNull(controller().create(first).getData());
        assertEquals(2, count("qms_inspection_procedure_task"));
        assertEquals(1, count("qms_inspection_event"));
        assertEquals(0, count("qms_inspection_event_outbox"));
    }

    @Test
    void anAmbiguousOldFullEquivalentTaskCannotBecomePartialSamplingOnAppend() {
        standard.setInspectionMethod("FIXED").setNum(2);
        long id =
                service.create(
                        command()
                                .setSourceRequestId("LOCAL-FIXED")
                                .setSourceRequestFingerprint(null));
        // 模拟升级前未留存完整依据的任务；新任务的冻结方案另有真实事务覆盖。
        db.update(
                "DELETE FROM mom_qms.qms_inspection_basis WHERE inspection_type='PROCEDURE' AND"
                        + " task_id=?",
                id);
        assertThrows(
                RuntimeException.class,
                () -> service.append(new InspectionAppendParam().setId(id).setNum(1)));
        assertEquals(2, value(id, "num"));
        assertEquals(2, detailValue(id, "total_num"));
    }

    @Test
    void undefinedOrNonFiniteResultsAreNotStored() {
        long id = service.create(command());
        for (Double v : new Double[] {null, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(
                    RuntimeException.class,
                    () ->
                            service.temporarilySave(
                                    new InspectionCheckResultParam()
                                            .setDetailId(detailId(id))
                                            .setRecord(
                                                    new InspectionItemResultRecord()
                                                            .setDoubleValue(v))));
        assertEquals(0, count("qms_inspection_procedure_item_result_record"));
    }

    @Test
    void failedFinalDownstreamRegistrationRollsBackConclusionButKeepsEarlierSamples() {
        long id = service.create(command());
        long d = detailId(id);
        service.temporarilySave(sample(d, 5));
        service.temporarilySave(sample(d, 6));
        doThrow(new IllegalStateException("outbox failure")).when(rework).publish("PROCEDURE", id);
        assertThrows(RuntimeException.class, () -> service.submitInspectionResult(id));
        assertNull(result(id));
        assertEquals(2, count("qms_inspection_procedure_item_result_record"));
        assertEquals(1, count("qms_inspection_event"));
    }

    @Test
    void mesFrozenSourceCannotBeAppendedOrReboundDirectly() {
        long id = service.create(command());
        assertThrows(
                RuntimeException.class,
                () -> service.append(new InspectionAppendParam().setId(id).setNum(1)));
        assertThrows(
                RuntimeException.class,
                () ->
                        service.update(
                                new InspectionProcedureCreateParam().setId(id).setStandardId(1L)));
        assertEquals(2, value(id, "num"));
    }

    @Test
    void legacyPrestartEditingBuildsDistinctCompleteItemSnapshots() {
        var local =
                command().setSourceRequestId("LOCAL-INTEGRITY").setSourceRequestFingerprint(null);
        long id = service.create(local);
        var second =
                new QmsQualityStandardItemEntity()
                        .setFactoryCode("F1")
                        .setStandardId(1L)
                        .setName("接线检查")
                        .setCheckType("BOOL");
        second.setId(502L);
        second.setTenantId(1L);
        when(items.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(item, second));
        service.update(new InspectionProcedureCreateParam().setId(id).setStandardId(1L));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_procedure_task_detail WHERE"
                                + " task_id=? AND del_flag='0'",
                        Integer.class,
                        id));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(DISTINCT standard_item_id) FROM"
                            + " mom_qms.qms_inspection_procedure_task_detail WHERE task_id=? AND"
                            + " del_flag='0'",
                        Integer.class,
                        id));
    }

    @Test
    void pendingLegacyAppendKeepsFullInspectionCountersConsistent() {
        standard.setInspectionMethod("RANDOM");
        long id =
                service.create(
                        command()
                                .setSourceRequestId("LOCAL-APPEND")
                                .setSourceRequestFingerprint(null));
        service.temporarilySave(sample(detailId(id), 5));
        service.append(new InspectionAppendParam().setId(id).setNum(1));
        assertEquals(3, value(id, "num"));
        assertEquals(3, value(id, "inspection_num"));
        assertEquals(3, detailValue(id, "total_num"));
        assertEquals(1, detailValue(id, "finish_num"));
        assertEquals(2, detailValue(id, "wait_num"));
    }

    @Test
    void taskAlreadyBeingInspectedCannotBeChangedIntoAnExemptionOrCanceled() {
        long id = service.create(command());
        service.temporarilySave(sample(detailId(id), 5));
        assertThrows(RuntimeException.class, () -> service.canceled(id, "late cancel"));
        assertThrows(RuntimeException.class, () -> service.exemption(id));
        assertNull(result(id));
        assertEquals(1, count("qms_inspection_procedure_item_result_record"));
    }

    @Test
    void cancellationCannotUseAWaitStateReadBeforeTheConcurrentSampleCommits() throws Exception {
        var c = command().setNum(1).setInspectionNum(1);
        c.setSourceRequestFingerprint(InspectionProcedureSourceContract.fingerprint(c));
        long id = service.create(c), detail = detailId(id);
        var authentication =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        fault.holdSample = true;
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            var collecting =
                    workers.submit(
                            () ->
                                    worker(
                                            "inspection-collector",
                                            authentication,
                                            () -> service.temporarilySave(sample(detail, 5))));
            assertTrue(fault.sampleHeld.await(3, TimeUnit.SECONDS));
            var canceling =
                    workers.submit(
                            () ->
                                    worker(
                                            "inspection-cancel",
                                            authentication,
                                            () -> service.canceled(id, "concurrent cancel")));
            // Old code reads WAIT before waiting on UPDATE; the fixed path waits at its SELECT FOR
            // UPDATE.
            fault.cancelReadComplete.await(300, TimeUnit.MILLISECONDS);
            fault.releaseSample.countDown();
            assertEquals("APPLIED", collecting.get(5, TimeUnit.SECONDS));
            assertEquals("REJECTED", canceling.get(5, TimeUnit.SECONDS));
            assertNull(result(id));
            assertEquals(1, count("qms_inspection_procedure_item_result_record"));
        } finally {
            fault.releaseSample.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void appendingCannotIgnoreANewMandatoryStandardItem() {
        var c = command().setSourceRequestId("LOCAL-ITEM-SET").setSourceRequestFingerprint(null);
        long id = service.create(c);
        var second =
                cn.hutool.core.bean.BeanUtil.copyProperties(
                        item, QmsQualityStandardItemEntity.class);
        second.setId(502L);
        when(items.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(item, second));
        // 模拟升级前未留存完整依据的任务；新任务的冻结方案另有真实事务覆盖。
        db.update(
                "DELETE FROM mom_qms.qms_inspection_basis WHERE inspection_type='PROCEDURE' AND"
                        + " task_id=?",
                id);
        assertThrows(
                RuntimeException.class,
                () -> service.append(new InspectionAppendParam().setId(id).setNum(1)));
        assertEquals(2, value(id, "num"));
        assertEquals(2, detailValue(id, "total_num"));
    }

    private String worker(
            String name,
            org.springframework.security.core.Authentication authentication,
            Runnable operation) {
        Thread.currentThread().setName(name);
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(authentication);
        try {
            operation.run();
            return "APPLIED";
        } catch (RuntimeException failure) {
            return "REJECTED";
        } finally {
            TenantContextHolder.clear();
            FactoryContextHolder.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentCollectorsAndSubmissionKeepExactFactsAndPublishOnlyOneConclusion(
            boolean secondCollects) throws Exception {
        var c = command().setNum(secondCollects ? 2 : 1).setInspectionNum(secondCollects ? 2 : 1);
        c.setSourceRequestFingerprint(InspectionProcedureSourceContract.fingerprint(c));
        long id = service.create(c), d = detailId(id);
        var authentication =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        fault.holdSample = true;
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            var first =
                    workers.submit(
                            () ->
                                    worker(
                                            "inspection-first",
                                            authentication,
                                            () -> service.temporarilySave(sample(d, 5))));
            assertTrue(fault.sampleHeld.await(3, TimeUnit.SECONDS));
            var second =
                    workers.submit(
                            () ->
                                    worker(
                                            "inspection-second",
                                            authentication,
                                            () -> {
                                                if (secondCollects)
                                                    service.temporarilySave(sample(d, 6));
                                                else service.submitInspectionResult(id);
                                            }));
            assertTrue(fault.additionalLockAttempt.await(3, TimeUnit.SECONDS));
            fault.releaseSample.countDown();
            assertEquals("APPLIED", first.get(5, TimeUnit.SECONDS));
            assertEquals("APPLIED", second.get(5, TimeUnit.SECONDS));
            service.submitInspectionResult(id);
            service.submitInspectionResult(id);
            assertEquals("QUALIFIED", result(id));
            assertEquals(
                    secondCollects ? 2 : 1, count("qms_inspection_procedure_item_result_record"));
            assertEquals(secondCollects ? 2 : 1, detailValue(id, "finish_num"));
            assertEquals(0, detailValue(id, "wait_num"));
            assertEquals(2, count("qms_inspection_procedure_history"));
            assertEquals(2, count("qms_inspection_event"));
            assertEquals(2, count("qms_inspection_event_outbox"));
            verify(rework, times(1)).publish("PROCEDURE", id);
        } finally {
            fault.releaseSample.countDown();
            workers.shutdownNow();
        }
    }

    private InspectionProcedureCreateParam command() {
        var c =
                new InspectionProcedureCreateParam()
                        .setTaskCode("PT1")
                        .setSourceTaskCode("PT1")
                        .setSourceRequestId("MES_QMS_V2:INTEGRITY")
                        .setMatCode("MAT")
                        .setProcessCode("PROC")
                        .setLabelType("FIRST")
                        .setStandardId(1L)
                        .setNum(2)
                        .setInspectionNum(2)
                        .setOrderNo("MT1");
        return c.setSourceRequestFingerprint(InspectionProcedureSourceContract.fingerprint(c));
    }

    private InspectionCheckResultParam sample(long detail, double value) {
        return new InspectionCheckResultParam()
                .setDetailId(detail)
                .setRecord(new InspectionItemResultRecord().setDoubleValue(value));
    }

    private InspectionCheckResultBatchParam batch(
            long task, InspectionCheckResultParam... results) {
        var batch = new InspectionCheckResultBatchParam();
        batch.setTaskId(task);
        batch.setResults(List.of(results));
        return batch;
    }

    private long detailId(long task) {
        return db.queryForObject(
                "SELECT id FROM mom_qms.qms_inspection_procedure_task_detail WHERE task_id=?",
                Long.class,
                task);
    }

    private int count(String table) {
        return db.queryForObject("SELECT COUNT(*) FROM mom_qms." + table, Integer.class);
    }

    private Integer value(long task, String column) {
        return db.queryForObject(
                "SELECT " + column + " FROM mom_qms.qms_inspection_procedure_task WHERE id=?",
                Integer.class,
                task);
    }

    private int detailValue(long task, String column) {
        return db.queryForObject(
                "SELECT "
                        + column
                        + " FROM mom_qms.qms_inspection_procedure_task_detail WHERE task_id=?",
                Integer.class,
                task);
    }

    private String result(long task) {
        return db.queryForObject(
                "SELECT result FROM mom_qms.qms_inspection_procedure_task WHERE id=?",
                String.class,
                task);
    }

    private void seedDetail(
            long id, long task, int total, int finished, int waiting, int ng, Boolean qualified) {
        db.update(
                "INSERT INTO"
                    + " mom_qms.qms_inspection_procedure_task_detail(id,tenant_id,factory_code,task_id,standard_item_id,name,check_type,upper_limit,lower_limit,total_num,finish_num,wait_num,unqualified_num,qualified_status)"
                    + " VALUES(?,1,'F1',?,501,'检查','RANG',10,0,?,?,?,?,?)",
                id,
                task,
                total,
                finished,
                waiting,
                ng,
                qualified);
    }

    private void table(MybatisConfiguration config, Class<?> type) {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(config, "procedure-integrity"), type);
        var info = TableInfoHelper.getTableInfo(type);
        String full =
                info.getTableName().contains(".")
                        ? info.getTableName()
                        : "mom_qms." + info.getTableName();
        StringBuilder ddl = new StringBuilder("CREATE TABLE " + full + "(id bigint PRIMARY KEY");
        for (var f : info.getFieldList()) {
            Class<?> t = f.getPropertyType();
            String sql =
                    t == Long.class || t == long.class
                            ? "bigint"
                            : t == Integer.class || t == int.class
                                    ? "int"
                                    : t == Boolean.class
                                            ? "boolean"
                                            : t == Date.class || t == LocalDateTime.class
                                                    ? "timestamp"
                                                    : t == BigDecimal.class
                                                            ? "decimal(24,8)"
                                                            : t == Double.class
                                                                    ? "double"
                                                                    : "varchar(64000)";
            ddl.append(',').append(f.getColumn()).append(' ').append(sql);
            if (f.getColumn().equals("del_flag")) ddl.append(" DEFAULT '0'");
        }
        db.execute(ddl.append(')').toString());
        String name = info.getTableName().substring(info.getTableName().lastIndexOf('.') + 1);
        db.execute("CREATE SYNONYM public." + name + " FOR " + full);
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
    static class WriteFault implements Interceptor {
        String lose;
        boolean holdSample;
        final AtomicBoolean held = new AtomicBoolean();
        final java.util.concurrent.atomic.AtomicInteger lockAttempts =
                new java.util.concurrent.atomic.AtomicInteger();
        final CountDownLatch sampleHeld = new CountDownLatch(1),
                releaseSample = new CountDownLatch(1),
                additionalLockAttempt = new CountDownLatch(1),
                cancelReadComplete = new CountDownLatch(1);

        public Object intercept(Invocation invocation) throws Throwable {
            var statement = (MappedStatement) invocation.getArgs()[0];
            if (statement.getId().endsWith("QmsInspectionProcedureTaskMapper.lockForInspection")
                    && lockAttempts.incrementAndGet() > 1) additionalLockAttempt.countDown();
            Object result = invocation.proceed();
            if (holdSample
                    && statement
                            .getId()
                            .endsWith("QmsInspectionProcedureItemResultRecordMapper.insert")
                    && held.compareAndSet(false, true)) {
                sampleHeld.countDown();
                if (!releaseSample.await(5, TimeUnit.SECONDS))
                    throw new IllegalStateException("sample test latch timed out");
            }
            if (Thread.currentThread().getName().equals("inspection-cancel")
                    && invocation.getMethod().getName().equals("query"))
                cancelReadComplete.countDown();
            return lose != null && statement.getId().endsWith(lose) ? 0 : result;
        }
    }
}
