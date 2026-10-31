package com.kitex.mom.qms.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.qms.dto.inspection.*;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.*;
import com.kitex.mom.qms.service.*;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real Product parent/detail/sample/history/event transactions; only external ports adapted. */
class QmsProductInspectionIntegrityTest {
    private QmsReworkCoordinationTransactionTest coordination;
    private QmsProcedureInspectionIntegrityTest fixture;
    private JdbcTemplate db;
    private InspectionProductTaskService service;
    private Object target;
    private QmsQualityStandardEntity standard;
    private QmsQualityStandardItemEntity item;
    private final Fault fault = new Fault();

    @BeforeEach
    void setup() throws Exception {
        coordination = new QmsReworkCoordinationTransactionTest();
        coordination.setup();
        fixture =
                (QmsProcedureInspectionIntegrityTest)
                        ReflectionTestUtils.getField(coordination, "fixture");
        db = (JdbcTemplate) ReflectionTestUtils.getField(fixture, "db");
        var rework =
                AopProxyUtils.getSingletonTarget(
                        ReflectionTestUtils.getField(coordination, "service"));
        service =
                (InspectionProductTaskService)
                        ReflectionTestUtils.getField(rework, "productTaskService");
        target = AopProxyUtils.getSingletonTarget(service);
        var sessions = (SqlSessionTemplate) ReflectionTestUtils.getField(fixture, "sessions");
        var config = (MybatisConfiguration) sessions.getConfiguration();
        ReflectionTestUtils.invokeMethod(
                fixture, "table", config, QmsInspectionProductItemResultRecordEntity.class);
        config.addMapper(QmsInspectionProductItemResultRecordMapper.class);
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

                                    public boolean ignoreTable(String t) {
                                        return t.toLowerCase(Locale.ROOT)
                                                .endsWith("_item_result_record");
                                    }
                                });
        config.addInterceptor(fault);
        var records = new InspectionProductItemResultRecordServiceImpl();
        ReflectionTestUtils.setField(
                records,
                "baseMapper",
                sessions.getMapper(QmsInspectionProductItemResultRecordMapper.class));
        ReflectionTestUtils.setField(target, "recordService", records);
        var handles = new InspectionProductHandleRecordServiceImpl();
        ReflectionTestUtils.setField(
                handles,
                "baseMapper",
                sessions.getMapper(QmsInspectionProductHandleRecordMapper.class));
        ReflectionTestUtils.setField(target, "handleRecordService", handles);
        db.update("DELETE FROM mom_qms.qms_inspection_product_handle_record");
        standard = (QmsQualityStandardEntity) ReflectionTestUtils.getField(fixture, "standard");
        standard.setInspectionType("PRODUCT");
        item = (QmsQualityStandardItemEntity) ReflectionTestUtils.getField(fixture, "item");
        db.update("DELETE FROM mom_qms.qms_inspection_product_history");
        db.execute(
                "CREATE UNIQUE INDEX product_source_request ON"
                        + " mom_qms.qms_inspection_product_task(tenant_id,source_request_id)");
    }

    @AfterEach
    void close() {
        fault.release.countDown();
        if (coordination != null) coordination.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FINISHED", "UNHANDLED", "CANCELED"})
    void endedTaskCannotCollectOrBeExempted(String status) {
        long id = create(2), d = detail(id);
        db.update(
                "UPDATE mom_qms.qms_inspection_product_task SET status=?,result='UN_QUALIFIED'"
                        + " WHERE id=?",
                status,
                id);
        assertThrows(RuntimeException.class, () -> service.temporarilySave(sample(d, 5D)));
        assertThrows(RuntimeException.class, () -> service.exemption(id));
        assertEquals(0, count("item_result_record"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void batchCannotMixParentsOrRepeatAnItem(boolean duplicate) {
        long first = create(2), second = create(2), d = detail(first);
        var other = duplicate ? sample(d, 6D) : sample(detail(second), 6D);
        assertThrows(
                RuntimeException.class,
                () -> service.batchTemporarilySave(batch(first, sample(d, 5D), other)));
        assertEquals(0, count("item_result_record"));
        assertEquals(0, detailValue(first, "finish_num"));
    }

    @Test
    void sampleCapacityIsNotExceeded() {
        long id = create(1), d = detail(id);
        service.temporarilySave(sample(d, 5D));
        assertThrows(RuntimeException.class, () -> service.temporarilySave(sample(d, 6D)));
        assertEquals(1, count("item_result_record"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL", "NaN", "Infinity"})
    void onlyFiniteActualNumbersAreAccepted(String value) {
        long id = create(1);
        assertThrows(
                RuntimeException.class,
                () ->
                        service.temporarilySave(
                                sample(
                                        detail(id),
                                        "NULL".equals(value) ? null : Double.valueOf(value))));
        assertEquals(0, count("item_result_record"));
    }

    @Test
    void judgementUsesFrozenRequirementAndServerOwnedIdentity() {
        long id = create(1), d = detail(id);
        item.setUpperLimit(1D);
        var input = sample(d, 5D);
        input.getRecord().setId(44L).setTaskId(800L).setDetailId(45L).setQualified(false);
        service.temporarilySave(input);
        assertTrue(
                db.queryForObject(
                        "SELECT qualified FROM mom_qms.qms_inspection_product_item_result_record",
                        Boolean.class));
        assertNotEquals(
                44L,
                db.queryForObject(
                        "SELECT id FROM mom_qms.qms_inspection_product_item_result_record",
                        Long.class));
        assertEquals(
                id,
                db.queryForObject(
                        "SELECT task_id FROM mom_qms.qms_inspection_product_item_result_record",
                        Long.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMPTY", "FAKE", "ORPHAN"})
    void aConclusionNeedsActualCompleteSamples(String corruption) {
        long id = create(1), d = detail(id);
        if ("EMPTY".equals(corruption))
            db.update("DELETE FROM mom_qms.qms_inspection_product_task_detail WHERE task_id=?", id);
        else {
            db.update(
                    "UPDATE mom_qms.qms_inspection_product_task_detail SET"
                            + " finish_num=1,wait_num=0,qualified_status=TRUE WHERE task_id=?",
                    id);
            if ("ORPHAN".equals(corruption))
                db.update(
                        "INSERT INTO"
                            + " mom_qms.qms_inspection_product_item_result_record(id,task_id,detail_id,qualified)"
                            + " VALUES(9,?,?,TRUE)",
                        id,
                        d + 1);
        }
        assertThrows(RuntimeException.class, () -> service.submitInspectionResult(id));
        assertNull(service.getById(id).getResult());
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void automaticAndManualConclusionsShareQuantitiesAndSideEffects(boolean automatic, boolean ng) {
        long id = create(1);
        var s = sample(detail(id), ng ? 20D : 5D);
        if (automatic) service.batchTemporarilySave(batch(id, s));
        else {
            service.temporarilySave(s);
            service.submitInspectionResult(id);
        }
        service.submitInspectionResult(id);
        var row = service.getById(id);
        assertEquals(ng ? "UN_QUALIFIED" : "QUALIFIED", row.getResult());
        assertEquals(ng ? 0 : 1, row.getUsableNum());
        assertEquals(ng ? 0 : 1, row.getHandleNum());
        verify(port(QmsReworkResultPublisher.class, "reworkResultPublisher"), times(1))
                .publish("PRODUCT", id);
        verify(port(QmsBadInfoService.class, "badInfoService"), times(ng ? 1 : 0)).add(any());
        verify(
                        port(QmsPlanEventOutboxService.class, "qmsPlanEventOutboxService"),
                        times(ng ? 1 : 0))
                .enqueueIsolation("PRODUCT", id);
        assertEquals(2, count("history"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "QmsInspectionProductItemResultRecordMapper.insert",
                "QmsInspectionProductTaskDetailMapper.updateById",
                "QmsInspectionProductTaskMapper.updateById"
            })
    void aRequiredWriteReturningZeroRollsBackEverything(String lose) {
        long id = create(1);
        fault.lose = lose;
        assertThrows(
                RuntimeException.class,
                () -> service.batchTemporarilySave(batch(id, sample(detail(id), 5D))));
        assertEquals(0, count("item_result_record"));
        assertEquals(0, detailValue(id, "finish_num"));
        assertNull(service.getById(id).getResult());
        assertEquals(1, count("history"));
    }

    @Test
    void downstreamFailureRollsBackConclusionAndBatch() {
        long id = create(1);
        doThrow(new IllegalStateException("outbox failed"))
                .when(port(QmsReworkResultPublisher.class, "reworkResultPublisher"))
                .publish("PRODUCT", id);
        assertThrows(
                RuntimeException.class,
                () -> service.batchTemporarilySave(batch(id, sample(detail(id), 5D))));
        assertEquals(0, count("item_result_record"));
        assertEquals(1, count("history"));
        assertNull(service.getById(id).getResult());
    }

    @Test
    void deniedFactoryWriteStopsBeforeTheParentLock() {
        long id = create(1);
        ReflectionTestUtils.setField(fixture, "authorized", false);
        assertThrows(RuntimeException.class, () -> service.temporarilySave(sample(detail(id), 5D)));
        assertThrows(RuntimeException.class, () -> service.submitInspectionResult(id));
        assertEquals(0, fault.locks.get());
        assertEquals(0, count("item_result_record"));
    }

    @Test
    void collectedTaskCannotChangeStandardOrBeCanceledOrExempted() {
        long id = create(2);
        service.temporarilySave(sample(detail(id), 5D));
        assertThrows(RuntimeException.class, () -> service.update(command().setId(id)));
        assertThrows(RuntimeException.class, () -> service.canceled(id, "test"));
        assertThrows(RuntimeException.class, () -> service.exemption(id));
    }

    @Test
    void oldWaitStateWithSamplesCannotBeExempted() {
        long id = create(2);
        service.temporarilySave(sample(detail(id), 5D));
        db.update("UPDATE mom_qms.qms_inspection_product_task SET status='WAIT' WHERE id=?", id);
        assertThrows(RuntimeException.class, () -> service.exemption(id));
        assertThrows(RuntimeException.class, () -> service.canceled(id, "test"));
    }

    @Test
    void boundReinspectionCannotChangeQuantityOrStandard() {
        long id = create(2);
        db.update(
                "UPDATE mom_qms.qms_inspection_product_task SET"
                        + " source_request_id='REWORK-REINSPECTION:1' WHERE id=?",
                id);
        assertThrows(RuntimeException.class, () -> service.update(command().setId(id)));
        assertThrows(
                RuntimeException.class,
                () -> service.append(new InspectionAppendParam().setId(id).setNum(1)));
    }

    @Test
    void fullAppendKeepsOutstandingSamplesAndFrozenThreshold() {
        long id = create(2);
        service.temporarilySave(sample(detail(id), 5D));
        service.append(new InspectionAppendParam().setId(id).setNum(1));
        assertEquals(3, service.getById(id).getInspectionNum());
        assertEquals(2, detailValue(id, "wait_num"));
    }

    @Test
    void changedCriteriaOrAmbiguousOldCeilingCannotBeAppended() {
        standard.setInspectionMethod("FIXED").setNum(2);
        long id = create(2);
        // 模拟升级前未留存完整依据的任务；新任务的冻结方案另有真实事务覆盖。
        db.update(
                "DELETE FROM mom_qms.qms_inspection_basis WHERE inspection_type='PRODUCT' AND"
                    + " task_id=?",
                id);
        assertThrows(
                RuntimeException.class,
                () -> service.append(new InspectionAppendParam().setId(id).setNum(1)));
        assertEquals(2, service.getById(id).getNum());
    }

    @Test
    void editingUnstartedTaskCreatesDistinctReplacementDetails() {
        long id = create(2);
        var second =
                cn.hutool.core.bean.BeanUtil.copyProperties(
                        item, QmsQualityStandardItemEntity.class);
        second.setId(502L);
        var items = port(QualityStandardItemService.class, "qualityStandardItemService");
        when(items.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(item, second));
        service.update(command().setId(id));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_product_task_detail WHERE"
                                + " task_id=? AND del_flag='0'",
                        Integer.class,
                        id));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(DISTINCT standard_item_id) FROM"
                                + " mom_qms.qms_inspection_product_task_detail WHERE task_id=? AND"
                                + " del_flag='0'",
                        Integer.class,
                        id));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void collectorsAndSubmissionSerializeAtTheRealParent(boolean secondCollects) throws Exception {
        long id = create(secondCollects ? 2 : 1), d = detail(id);
        var auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        fault.hold = true;
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first =
                    workers.submit(
                            () -> worker(auth, () -> service.temporarilySave(sample(d, 5D))));
            assertTrue(fault.held.await(3, TimeUnit.SECONDS));
            var second =
                    workers.submit(
                            () ->
                                    worker(
                                            auth,
                                            () -> {
                                                if (secondCollects)
                                                    service.temporarilySave(sample(d, 6D));
                                                else service.submitInspectionResult(id);
                                            }));
            assertTrue(fault.secondLock.await(3, TimeUnit.SECONDS));
            fault.release.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS));
            assertTrue(second.get(5, TimeUnit.SECONDS));
            service.submitInspectionResult(id);
            service.submitInspectionResult(id);
            assertEquals("QUALIFIED", service.getById(id).getResult());
            assertEquals(secondCollects ? 2 : 1, count("item_result_record"));
            verify(port(QmsReworkResultPublisher.class, "reworkResultPublisher"), times(1))
                    .publish("PRODUCT", id);
        } finally {
            fault.release.countDown();
            workers.shutdownNow();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "POST,create",
        "PUT,update",
        "POST,append",
        "POST,canceled",
        "POST,exemption",
        "POST,temporarilySave",
        "POST,batchTemporarilySave",
        "POST,submitInspectionResult",
        "POST,handleFinish",
        "POST,handleRework"
    })
    void mutationEndpointsRequireTheirExistingAction(String method, String path) throws Exception {
        var history = ReflectionTestUtils.getField(fixture, "historyFixture");
        var mvc =
                (org.springframework.test.web.servlet.MockMvc)
                        ReflectionTestUtils.getField(history, "mvc");
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                                        org.springframework.http.HttpMethod.valueOf(method),
                                        "/inspection/product/task/" + path)
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isForbidden());
    }

    @Test
    void manualCreationRequiresFactoryWriteAndCannotClaimInternalSource() {
        var controller =
                new com.kitex.mom.qms.controller.InspectionProductTaskController(
                        service,
                        mock(InspectionProductHistoryService.class),
                        mock(InspectionProductHandleRecordService.class),
                        mock(QmsReworkOrderService.class),
                        mock(QmsInspectionEventService.class));
        assertThrows(
                RuntimeException.class,
                () -> controller.create(command().setSourceRequestId("REWORK-REINSPECTION:1")));
        ReflectionTestUtils.setField(fixture, "authorized", false);
        assertThrows(RuntimeException.class, () -> controller.create(command()));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_product_task", Integer.class));
    }

    @Test
    void dispositionAlsoRequiresActualFactoryWriteBeforeAnyLock() {
        db.update(
                "UPDATE mom_qms.qms_inspection_product_task SET"
                        + " status='UNHANDLED',handle_num=0,usable_num=0 WHERE id=800");
        ReflectionTestUtils.setField(fixture, "authorized", false);
        assertThrows(
                RuntimeException.class,
                () ->
                        service.handleFinish(
                                new com.kitex.mom.qms.dto.resp.HandleFinishDTO()
                                        .setId(800L)
                                        .setHandleNum(1)
                                        .setHandleResult("SMASH")
                                        .setHandleRemark("test")));
        assertEquals(0, fault.locks.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FACTORY", "TENANT"})
    void actualTaskCannotBeMutatedFromAnotherOrganization(String changed) {
        long id = create(1), d = detail(id);
        db.update(
                "UPDATE mom_qms.qms_inspection_product_task SET "
                        + ("FACTORY".equals(changed) ? "factory_code='F2'" : "tenant_id=2")
                        + " WHERE id=?",
                id);
        assertThrows(RuntimeException.class, () -> service.temporarilySave(sample(d, 5D)));
        assertThrows(RuntimeException.class, () -> service.submitInspectionResult(id));
        assertEquals(0, count("item_result_record"));
    }

    @Test
    void booleanVerdictMustBeExplicit() {
        item.setCheckType("BOOL");
        long id = create(1), d = detail(id);
        assertThrows(RuntimeException.class, () -> service.temporarilySave(sample(d, 5D)));
        var s = sample(d, null);
        s.getRecord().setQualified(true);
        service.temporarilySave(s);
        service.submitInspectionResult(id);
        assertEquals("QUALIFIED", service.getById(id).getResult());
    }

    @Test
    void emptyBatchCannotTriggerConclusion() {
        long id = create(1);
        assertThrows(RuntimeException.class, () -> service.batchTemporarilySave(batch(id)));
        assertNull(service.getById(id).getResult());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "QmsInspectionProductTaskMapper.updateById",
                "QmsInspectionProductHandleRecordMapper.insert"
            })
    void dispositionRequiredWritesAreAtomic(String lose) {
        db.update(
                "UPDATE mom_qms.qms_inspection_product_task SET"
                        + " status='UNHANDLED',handle_num=0,usable_num=0 WHERE id=800");
        fault.lose = lose;
        assertThrows(
                RuntimeException.class,
                () ->
                        service.handleFinish(
                                new com.kitex.mom.qms.dto.resp.HandleFinishDTO()
                                        .setId(800L)
                                        .setHandleNum(1)
                                        .setHandleResult("SMASH")
                                        .setHandleRemark("test")));
        assertEquals(0, service.getById(800L).getHandleNum());
        assertEquals(0, count("handle_record"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void waitingCancellationOrExemptionCannotOverrideACommittedSample(boolean exempt)
            throws Exception {
        long id = create(2), d = detail(id);
        var auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        fault.hold = true;
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first =
                    workers.submit(
                            () -> worker(auth, () -> service.temporarilySave(sample(d, 5D))));
            assertTrue(fault.held.await(3, TimeUnit.SECONDS));
            var second =
                    workers.submit(
                            () ->
                                    worker(
                                            auth,
                                            () -> {
                                                if (exempt) service.exemption(id);
                                                else service.canceled(id, "test");
                                            }));
            assertTrue(fault.secondLock.await(3, TimeUnit.SECONDS));
            fault.release.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS));
            assertInstanceOf(
                    RuntimeException.class,
                    assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS))
                            .getCause());
            assertNull(service.getById(id).getResult());
            assertEquals(1, count("item_result_record"));
        } finally {
            fault.release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void creationReplayCannotReadAnExistingTaskFromAnotherFactory() {
        var c = command().setSourceTaskCode("PT1");
        db.update(
                "UPDATE mom_qms.qms_inspection_product_task SET"
                    + " factory_code='F2',source_request_id=?,source_task_code='PT1' WHERE id=800",
                c.getSourceRequestId());
        assertThrows(RuntimeException.class, () -> service.create(c));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_inspection_product_task WHERE"
                                + " factory_code='F1' AND source_request_id=?",
                        Integer.class,
                        c.getSourceRequestId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUANTITY", "STANDARD", "PROCESS", "ORDER", "BATCH"})
    void anExistingSourceRequestCannotAcknowledgeADifferentInspection(String changed) {
        var c = command();
        service.create(c);
        switch (changed) {
            case "QUANTITY" -> c.setNum(1);
            case "STANDARD" -> c.setStandardId(2L);
            case "PROCESS" -> c.setProcessCode("OTHER");
            case "ORDER" -> c.setOrderNo("OTHER");
            case "BATCH" -> c.setBatchNo("OTHER");
            default -> throw new AssertionError(changed);
        }
        assertThrows(RuntimeException.class, () -> service.create(c));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADDED", "REMOVED", "DUPLICATE_SAVED"})
    void appendRequiresTheCompleteUnchangedStandardItemSet(String change) {
        long id = create(2);
        var items = port(QualityStandardItemService.class, "qualityStandardItemService");
        if ("ADDED".equals(change)) {
            var second =
                    cn.hutool.core.bean.BeanUtil.copyProperties(
                            item, QmsQualityStandardItemEntity.class);
            second.setId(502L);
            when(items.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                    .thenReturn(List.of(item, second));
        } else if ("REMOVED".equals(change)) {
            when(items.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                    .thenReturn(List.of());
        } else {
            db.update(
                    "INSERT INTO"
                        + " mom_qms.qms_inspection_product_task_detail(id,tenant_id,factory_code,task_id,standard_item_id,name,check_type,lower_limit,upper_limit,total_num,finish_num,wait_num,unqualified_num,records)"
                        + " VALUES(555,1,'F1',?,501,'duplicate','RANG',0,10,2,0,2,0,'[]')",
                    id);
        }
        // 模拟升级前未留存完整依据的任务；新任务的冻结方案另有真实事务覆盖。
        db.update(
                "DELETE FROM mom_qms.qms_inspection_basis WHERE inspection_type='PRODUCT' AND"
                    + " task_id=?",
                id);
        assertThrows(
                RuntimeException.class,
                () -> service.append(new InspectionAppendParam().setId(id).setNum(1)));
        assertEquals(2, service.getById(id).getNum());
    }

    @Test
    void identicalCreationReplayUsesStoredFactsAfterTheStandardChanges() {
        var c = command().setNum(1).setInspectionNum(1);
        service.create(c);
        long id =
                db.queryForObject(
                        "SELECT id FROM mom_qms.qms_inspection_product_task WHERE"
                                + " source_request_id=?",
                        Long.class,
                        c.getSourceRequestId());
        service.batchTemporarilySave(batch(id, sample(detail(id), 5D)));
        standard.setPublished(0).setInspectionType("PURCHASE");
        service.create(c);
        assertEquals(1, count("item_result_record"));
        assertEquals(2, count("history"));
        assertEquals("QUALIFIED", service.getById(id).getResult());
    }

    private boolean worker(org.springframework.security.core.Authentication auth, Runnable action) {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(auth);
        try {
            action.run();
            return true;
        } finally {
            TenantContextHolder.clear();
            FactoryContextHolder.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private <T> T port(Class<T> type, String name) {
        return type.cast(ReflectionTestUtils.getField(target, name));
    }

    private InspectionProductCreateParam command() {
        return new InspectionProductCreateParam()
                .setMatCode("MAT")
                .setProcessCode("PROC")
                .setStandardId(1L)
                .setNum(2)
                .setInspectionNum(2)
                .setOrderNo("MT1")
                .setSourceRequestId("LOCAL-" + UUID.randomUUID());
    }

    private long create(int n) {
        var c = command().setNum(n).setInspectionNum(n);
        service.create(c);
        return db.queryForObject(
                "SELECT id FROM mom_qms.qms_inspection_product_task WHERE source_request_id=?",
                Long.class,
                c.getSourceRequestId());
    }

    private long detail(long id) {
        return db.queryForObject(
                "SELECT id FROM mom_qms.qms_inspection_product_task_detail WHERE task_id=? AND"
                        + " del_flag='0'",
                Long.class,
                id);
    }

    private int detailValue(long id, String c) {
        return db.queryForObject(
                "SELECT "
                        + c
                        + " FROM mom_qms.qms_inspection_product_task_detail WHERE task_id=? AND"
                        + " del_flag='0'",
                Integer.class,
                id);
    }

    private int count(String suffix) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM mom_qms.qms_inspection_product_" + suffix, Integer.class);
    }

    private InspectionCheckResultParam sample(long detail, Double value) {
        return new InspectionCheckResultParam()
                .setDetailId(detail)
                .setRecord(new InspectionItemResultRecord().setDoubleValue(value));
    }

    private InspectionCheckResultBatchParam batch(long id, InspectionCheckResultParam... results) {
        var p = new InspectionCheckResultBatchParam();
        p.setTaskId(id);
        p.setResults(List.of(results));
        return p;
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
        String lose;
        boolean hold;
        final AtomicInteger locks = new AtomicInteger();
        final CountDownLatch held = new CountDownLatch(1),
                release = new CountDownLatch(1),
                secondLock = new CountDownLatch(1);

        public Object intercept(Invocation i) throws Throwable {
            var s = (MappedStatement) i.getArgs()[0];
            if (s.getId().endsWith("QmsInspectionProductTaskMapper.lockForInspection")
                    && locks.incrementAndGet() > 1) secondLock.countDown();
            Object result = i.proceed();
            if (hold && s.getId().endsWith("QmsInspectionProductItemResultRecordMapper.insert")) {
                held.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("latch");
            }
            return lose != null && s.getId().endsWith(lose) ? 0 : result;
        }
    }
}
