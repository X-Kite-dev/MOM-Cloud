package com.kitex.mom.qms.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.qms.dto.QmsReworkCompletionDTO;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.*;
import com.kitex.mom.qms.service.*;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real QMS coordination/handle/inspection/history transactions; MES remote and product generator
 * are adapted.
 */
class QmsReworkCoordinationTransactionTest {
    private QmsProcedureInspectionIntegrityTest fixture;
    private JdbcTemplate db;
    private SqlSessionTemplate sessions;
    private QmsReworkOrderService service;
    private RemoteMesTaskService remote;
    private PlatformTransactionManager transactions;
    private final Fault fault = new Fault();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean holdRemote;
    private final CountDownLatch remoteHeld = new CountDownLatch(1),
            releaseRemote = new CountDownLatch(1),
            secondRemote = new CountDownLatch(1);

    @BeforeEach
    void setup() throws Exception {
        fixture = new QmsProcedureInspectionIntegrityTest();
        fixture.setup();
        db = (JdbcTemplate) ReflectionTestUtils.getField(fixture, "db");
        sessions = (SqlSessionTemplate) ReflectionTestUtils.getField(fixture, "sessions");
        var history = ReflectionTestUtils.getField(fixture, "historyFixture");
        transactions =
                (PlatformTransactionManager) ReflectionTestUtils.getField(history, "transactions");
        var config = (MybatisConfiguration) sessions.getConfiguration();
        for (var entity :
                List.of(
                        QmsReworkOrderEntity.class,
                        QmsInspectionProcedureHandleRecordEntity.class,
                        QmsInspectionProductHandleRecordEntity.class,
                        QmsInspectionProductTaskDetailEntity.class))
            ReflectionTestUtils.invokeMethod(fixture, "table", config, entity);
        for (var mapper :
                List.of(
                        QmsReworkOrderMapper.class,
                        QmsInspectionProcedureHandleRecordMapper.class,
                        QmsInspectionProductTaskMapper.class,
                        QmsInspectionProductHandleRecordMapper.class,
                        QmsInspectionProductTaskDetailMapper.class))
            if (!config.hasMapper(mapper)) config.addMapper(mapper);
        config.addInterceptor(fault);
        db.execute(
                "CREATE TABLE mom_qms.qms_rework_completion_receipt(id BIGINT PRIMARY KEY,tenant_id"
                    + " BIGINT NOT NULL,factory_code VARCHAR(200) NOT NULL,inspection_type"
                    + " VARCHAR(20) NOT NULL,order_id BIGINT NOT NULL,child_task_id BIGINT NOT"
                    + " NULL,request_id VARCHAR(200) NOT NULL,payload CLOB NOT"
                    + " NULL,payload_fingerprint VARCHAR(64) NOT NULL,received_by"
                    + " BIGINT,received_at TIMESTAMP NOT"
                    + " NULL,UNIQUE(tenant_id,factory_code,order_id),UNIQUE(tenant_id,factory_code,inspection_type,child_task_id),UNIQUE(tenant_id,request_id))");
        db.execute(
                "CREATE ALIAS mom_qms.mom_rework_completion_receipt_ready_v1 FOR"
                    + " 'com.kitex.mom.qms.service.impl.QmsInspectionHistoryTransactionTest.installedVersion'");

        db.update(
                "UPDATE mom_qms.qms_inspection_procedure_task SET"
                    + " source_task_code='P0',order_no='MT0',mat_code='MAT',process_code='PROC',standard_id=1,standard_name='标准',label_type='FIRST',handle_num=2,usable_num=0,result='UN_QUALIFIED',status='FINISHED'"
                    + " WHERE id=700");
        db.update(
                "INSERT INTO"
                    + " mom_qms.qms_inspection_procedure_handle_record(id,tenant_id,factory_code,task_id,handle_num,handle_result,handle_remark,status)"
                    + " VALUES(80,1,'F1',700,2,'REWORK','接线返修',FALSE)");
        db.update("DELETE FROM mom_qms.qms_inspection_product_task");
        db.update(
                "INSERT INTO"
                    + " mom_qms.qms_inspection_product_task(id,tenant_id,factory_code,source_task_code,order_no,mat_code,process_code,standard_id,inspection_num,num,handle_num,usable_num,result,status)"
                    + " VALUES(800,1,'F1','P0','MT0','MAT','PROC',1,2,2,2,0,'UN_QUALIFIED','FINISHED')");
        db.update(
                "INSERT INTO"
                    + " mom_qms.qms_inspection_product_handle_record(id,tenant_id,factory_code,task_id,handle_num,handle_result,handle_remark,status)"
                    + " VALUES(81,1,'F1',800,2,'REWORK','接线返修',FALSE)");
        remote = mock(RemoteMesTaskService.class);
        when(remote.addRework(any()))
                .thenAnswer(
                        c -> {
                            int call = calls.incrementAndGet();
                            if (call == 2) secondRemote.countDown();
                            if (holdRemote && call == 1) {
                                remoteHeld.countDown();
                                if (!releaseRemote.await(5, TimeUnit.SECONDS))
                                    throw new IllegalStateException("remote latch timed out");
                            }
                            return R.ok("RW-MT1", null);
                        });
        var productDetails = new QmsInspectionProductTaskDetailServiceImpl();
        ReflectionTestUtils.setField(
                productDetails,
                "baseMapper",
                sessions.getMapper(QmsInspectionProductTaskDetailMapper.class));
        var productDeps = new HashMap<Class<?>, Object>();
        productDeps.put(QmsInspectionProductTaskDetailService.class, productDetails);
        productDeps.put(
                QualityStandardService.class, ReflectionTestUtils.getField(fixture, "standards"));
        productDeps.put(
                QualityStandardItemService.class, ReflectionTestUtils.getField(fixture, "items"));
        productDeps.put(
                QualitySamplingStrategiesService.class,
                ReflectionTestUtils.getField(fixture, "sampling"));
        productDeps.put(
                InspectionProductHistoryService.class,
                ReflectionTestUtils.getField(history, "product"));
        productDeps.put(
                QmsInspectionHistorySupport.class,
                ReflectionTestUtils.getField(
                        org.springframework.aop.framework.AopProxyUtils.getSingletonTarget(
                                ReflectionTestUtils.getField(fixture, "service")),
                        "inspectionAccess"));
        productDeps.put(
                QmsInspectionBasisService.class,
                ReflectionTestUtils.getField(
                        org.springframework.aop.framework.AopProxyUtils.getSingletonTarget(
                                ReflectionTestUtils.getField(fixture, "service")),
                        "inspectionBasis"));
        var productConstructor =
                InspectionProductTaskServiceImpl.class.getDeclaredConstructors()[0];
        Object[] productArguments =
                Arrays.stream(productConstructor.getParameterTypes())
                        .map(t -> productDeps.containsKey(t) ? productDeps.get(t) : mock(t))
                        .toArray();
        var productTarget = productConstructor.newInstance(productArguments);
        ReflectionTestUtils.setField(
                productTarget,
                "baseMapper",
                sessions.getMapper(QmsInspectionProductTaskMapper.class));
        var productProxy = new ProxyFactory(productTarget);
        productProxy.setInterfaces(InspectionProductTaskService.class);
        productProxy.addAdvice(
                new TransactionInterceptor(
                        transactions, new AnnotationTransactionAttributeSource()));
        var product = (InspectionProductTaskService) productProxy.getProxy();
        var deps = new HashMap<Class<?>, Object>();
        deps.put(
                QmsInspectionProcedureHandleRecordMapper.class,
                sessions.getMapper(QmsInspectionProcedureHandleRecordMapper.class));
        deps.put(
                QmsInspectionProductHandleRecordMapper.class,
                sessions.getMapper(QmsInspectionProductHandleRecordMapper.class));
        deps.put(
                QmsInspectionPurchaseHandleRecordMapper.class,
                mock(QmsInspectionPurchaseHandleRecordMapper.class));
        deps.put(
                QmsInspectionProcedureTaskMapper.class,
                sessions.getMapper(QmsInspectionProcedureTaskMapper.class));
        deps.put(
                QmsInspectionProductTaskMapper.class,
                sessions.getMapper(QmsInspectionProductTaskMapper.class));
        deps.put(RemoteMesTaskService.class, remote);
        deps.put(
                InspectionProcedureTaskService.class,
                ReflectionTestUtils.getField(fixture, "service"));
        deps.put(InspectionProductTaskService.class, product);
        deps.put(QmsReworkResultPublisher.class, mock(QmsReworkResultPublisher.class));
        deps.put(
                com.kitex.common.security.component.PermissionService.class,
                new com.kitex.common.security.component.PermissionService());
        @SuppressWarnings("unchecked")
        var historyDeps =
                (Map<Class<?>, Object>) ReflectionTestUtils.getField(history, "dependencies");
        deps.put(
                QmsInspectionHistorySupport.class,
                historyDeps.get(QmsInspectionHistorySupport.class));
        if (deps.get(QmsInspectionHistorySupport.class) == null) {
            var core =
                    org.springframework.aop.framework.AopProxyUtils.getSingletonTarget(
                            ReflectionTestUtils.getField(fixture, "service"));
            deps.put(
                    QmsInspectionHistorySupport.class,
                    ReflectionTestUtils.getField(core, "inspectionAccess"));
        }
        if (!config.hasMapper(QmsReworkCompletionReceiptMapper.class))
            config.addMapper(QmsReworkCompletionReceiptMapper.class);
        if (!config.hasMapper(QmsInspectionTraceMapper.class))
            config.addMapper(QmsInspectionTraceMapper.class);
        var receiptPermissions = mock(com.kitex.common.security.component.PermissionService.class);
        when(receiptPermissions.hasPermission("procedureTask:show")).thenReturn(true);
        when(receiptPermissions.hasPermission("productTask:show")).thenReturn(true);
        var receipts =
                new QmsReworkCompletionReceiptService(
                        sessions.getMapper(QmsReworkCompletionReceiptMapper.class),
                        sessions.getMapper(QmsReworkOrderMapper.class),
                        sessions.getMapper(QmsInspectionTraceMapper.class),
                        (QmsInspectionHistorySupport) deps.get(QmsInspectionHistorySupport.class),
                        receiptPermissions);
        var receiptProxy = new ProxyFactory(receipts);
        receiptProxy.setProxyTargetClass(true);
        receiptProxy.addAdvice(
                new TransactionInterceptor(
                        transactions, new AnnotationTransactionAttributeSource()));
        deps.put(QmsReworkCompletionReceiptService.class, receiptProxy.getProxy());
        var constructor = QmsReworkOrderServiceImpl.class.getDeclaredConstructors()[0];
        var target =
                constructor.newInstance(
                        Arrays.stream(constructor.getParameterTypes()).map(deps::get).toArray());
        ReflectionTestUtils.setField(
                target, "baseMapper", sessions.getMapper(QmsReworkOrderMapper.class));
        var proxy = new ProxyFactory(target);
        proxy.setInterfaces(QmsReworkOrderService.class);
        proxy.addAdvice(
                new TransactionInterceptor(
                        transactions, new AnnotationTransactionAttributeSource()));
        service = (QmsReworkOrderService) proxy.getProxy();
    }

    @AfterEach
    void close() {
        releaseRemote.countDown();
        if (fixture != null) fixture.close();
    }

    @Test
    void validReworkCompletionCreatesOneRealProcedureInspectionAndReplaysIt() {
        var order = service.create("PROCEDURE", 80L);
        assertEquals("MES_CREATED", order.getStatus(), order.getLastError());
        long inspection = service.completeFromMes(command(order));
        assertTrue(inspection > 0);
        assertEquals(inspection, service.completeFromMes(command(order)));
        assertEquals(2, count("qms_inspection_procedure_task"));
        assertEquals(1, count("qms_inspection_procedure_history"));
        assertEquals(1, count("qms_inspection_event"));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT num FROM mom_qms.qms_inspection_procedure_task WHERE id=?",
                        Integer.class,
                        inspection));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT callback_count FROM mom_qms.qms_rework_order WHERE id=?",
                        Integer.class,
                        order.getId()));
    }

    @Test
    void failedMesResponseCannotBecomeACompletedDispatchEvenWhenItContainsAWorkorder() {
        var response = R.<String>failed("MES rejected");
        response.setData("OLD-WORKORDER");
        when(remote.addRework(any())).thenReturn(response);
        var row = service.create("PROCEDURE", 80L);
        assertEquals("DISPATCH_FAILED", row.getStatus());
        assertNull(row.getMesTaskCode());
        assertFalse(
                db.queryForObject(
                        "SELECT status FROM mom_qms.qms_inspection_procedure_handle_record WHERE"
                                + " id=80",
                        Boolean.class));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "QmsReworkOrderMapper.insert",
                "QmsReworkOrderMapper.updateById",
                "QmsInspectionProcedureHandleRecordMapper.updateById"
            })
    void failedNecessaryDispatchWriteRollsBackInsteadOfReturningSuccess(String statement) {
        fault.lose = statement;
        assertThrows(RuntimeException.class, () -> service.create("PROCEDURE", 80L));
        assertEquals(0, count("qms_rework_order"));
        assertFalse(
                db.queryForObject(
                        "SELECT status FROM mom_qms.qms_inspection_procedure_handle_record WHERE"
                                + " id=80",
                        Boolean.class));
        if (!statement.contains("HandleRecord")) assertEquals(0, calls.get());
    }

    @Test
    void changedDispositionCannotReuseAnExistingReworkOrder() {
        service.create("PROCEDURE", 80L);
        db.update(
                "UPDATE mom_qms.qms_inspection_procedure_handle_record SET handle_num=1 WHERE"
                        + " id=80");
        assertThrows(RuntimeException.class, () -> service.create("PROCEDURE", 80L));
        assertEquals(1, count("qms_rework_order"));
    }

    @Test
    void foreignFactoryDispositionCannotCreateRework() {
        db.update(
                "UPDATE mom_qms.qms_inspection_procedure_handle_record SET factory_code='F2' WHERE"
                        + " id=80");
        assertThrows(RuntimeException.class, () -> service.create("PROCEDURE", 80L));
        assertEquals(0, count("qms_rework_order"));
        assertEquals(0, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "3", "0", "-1", "2.5"})
    void completedQuantityMustExactlyMatchTheDisposedQuantity(String quantity) {
        var row = service.create("PROCEDURE", 80L);
        var c = command(row);
        c.setCompletedQuantity(new BigDecimal(quantity));
        assertThrows(RuntimeException.class, () -> service.completeFromMes(c));
        assertEquals(1, count("qms_inspection_procedure_task"));
        assertNull(boundInspection(row));
    }

    @Test
    void aFailedFinalBindingRollsBackTheRealChildHistoryAndEvent() {
        var row = service.create("PROCEDURE", 80L);
        fault.lose = "QmsReworkOrderMapper.updateById";
        assertThrows(RuntimeException.class, () -> service.completeFromMes(command(row)));
        assertEquals(1, count("qms_inspection_procedure_task"));
        assertEquals(0, count("qms_inspection_procedure_history"));
        assertEquals(0, count("qms_inspection_event"));
        assertNull(boundInspection(row));
    }

    @ParameterizedTest
    @ValueSource(strings = {"quantity", "process", "operation"})
    void replayWithAChangedCompletionCannotUseTheAlreadyBoundChild(String change) {
        var row = service.create("PROCEDURE", 80L);
        long id = service.completeFromMes(command(row));
        var c = command(row);
        if (change.equals("quantity")) c.setCompletedQuantity(BigDecimal.ONE);
        if (change.equals("process")) c.setProcessCode("OTHER");
        if (change.equals("operation")) c.setMesProduceTaskCode("OTHER-PRODUCE");
        assertThrows(RuntimeException.class, () -> service.completeFromMes(c));
        assertEquals(id, boundInspection(row));
    }

    @ParameterizedTest
    @ValueSource(strings = {"zero", "missing", "foreignFactory", "wrongSource"})
    void aDamagedHistoricalChildBindingIsNotReturnedAsASuccess(String damage) {
        var row = service.create("PROCEDURE", 80L);
        long id = service.completeFromMes(command(row));
        if (damage.equals("zero"))
            db.update(
                    "UPDATE mom_qms.qms_rework_order SET reinspection_task_id=0 WHERE id=?",
                    row.getId());
        if (damage.equals("missing"))
            db.update("DELETE FROM mom_qms.qms_inspection_procedure_task WHERE id=?", id);
        if (damage.equals("foreignFactory"))
            db.update(
                    "UPDATE mom_qms.qms_inspection_procedure_task SET factory_code='F2' WHERE id=?",
                    id);
        if (damage.equals("wrongSource"))
            db.update(
                    "UPDATE mom_qms.qms_inspection_procedure_task SET source_request_id='OTHER'"
                            + " WHERE id=?",
                    id);
        assertThrows(RuntimeException.class, () -> service.completeFromMes(command(row)));
    }

    @Test
    void aForeignFactoryOrderCannotReceiveTheCurrentFactorysCompletion() {
        var row = service.create("PROCEDURE", 80L);
        db.update("UPDATE mom_qms.qms_rework_order SET factory_code='F2' WHERE id=?", row.getId());
        assertThrows(RuntimeException.class, () -> service.completeFromMes(command(row)));
        assertEquals(1, count("qms_inspection_procedure_task"));
    }

    @Test
    void incompatibleProcessCannotBeBoundToTheReinspection() {
        var row = service.create("PROCEDURE", 80L);
        var c = command(row);
        c.setProcessCode("OTHER");
        assertThrows(RuntimeException.class, () -> service.completeFromMes(c));
        assertNull(boundInspection(row));
    }

    @Test
    void aProductReinspectionReusesTheExactSavedLink() {
        ((QmsQualityStandardEntity) ReflectionTestUtils.getField(fixture, "standard"))
                .setInspectionType("PRODUCT");
        var row = service.create("PRODUCT", 81L);
        long id = service.completeFromMes(command(row));
        assertEquals(id, service.completeFromMes(command(row)));
        assertEquals(2, count("qms_inspection_product_task"));
    }

    @Test
    void internalCompletionIsNotAnExternalUserCommand() {
        var row = service.create("PROCEDURE", 80L);
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var proxy =
                new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(
                        new com.kitex.mom.qms.controller.QmsReworkOrderController(service));
        proxy.addAspect(new com.kitex.common.security.component.MomSecurityInnerAspect(request));
        var controller = (com.kitex.mom.qms.controller.QmsReworkOrderController) proxy.getProxy();
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> controller.mesComplete(command(row)));
        assertNull(boundInspection(row));
        request.addHeader(
                com.kitex.common.core.constant.SecurityConstants.FROM,
                com.kitex.common.core.constant.SecurityConstants.FROM_IN);
        assertNotNull(controller.mesComplete(command(row)).getData());
    }

    @Test
    void manualCreationRequiresTheFactoryWriteGrant() {
        ReflectionTestUtils.setField(fixture, "authorized", false);
        var controller = new com.kitex.mom.qms.controller.QmsReworkOrderController(service);
        assertThrows(RuntimeException.class, () -> controller.create("PROCEDURE", 80L));
        assertEquals(0, count("qms_rework_order"));
        assertEquals(0, calls.get());
    }

    @Test
    void aProcedureHandlerCannotUseTheGenericEntryForProductRework() {
        var auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(
                                auth.getPrincipal(),
                                "N/A",
                                List.of(
                                        new org.springframework.security.core.authority
                                                .SimpleGrantedAuthority("procedureTask:handle"))));
        var controller = new com.kitex.mom.qms.controller.QmsReworkOrderController(service);
        assertThrows(RuntimeException.class, () -> controller.create("PRODUCT", 81L));
        assertEquals(0, count("qms_rework_order"));
    }

    @Test
    void concurrentCreationSerializesAtTheOriginalDisposition() throws Exception {
        holdRemote = true;
        var auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        var workers = Executors.newFixedThreadPool(2);
        try {
            var one =
                    workers.submit(
                            () -> scoped(auth, () -> service.create("PROCEDURE", 80L).getId()));
            assertTrue(remoteHeld.await(3, TimeUnit.SECONDS));
            var two =
                    workers.submit(
                            () -> scoped(auth, () -> service.create("PROCEDURE", 80L).getId()));
            secondRemote.await(350, TimeUnit.MILLISECONDS);
            releaseRemote.countDown();
            assertEquals(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS));
            assertEquals(1, count("qms_rework_order"));
            assertEquals(1, calls.get());
        } finally {
            releaseRemote.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void aLostFinalDispatchAcknowledgementRollsBackTheHandleAndJournal() {
        fault.lose = "QmsReworkOrderMapper.updateById";
        fault.loseAt = 2;
        assertThrows(RuntimeException.class, () -> service.create("PROCEDURE", 80L));
        assertEquals(1, calls.get());
        assertEquals(0, count("qms_rework_order"));
        assertFalse(
                db.queryForObject(
                        "SELECT status FROM mom_qms.qms_inspection_procedure_handle_record WHERE"
                                + " id=80",
                        Boolean.class));
    }

    @Test
    void remoteUnknownRetriesTheSameOrderAndClearsTheOldError() {
        when(remote.addRework(any()))
                .thenThrow(new IllegalStateException("network unknown"))
                .thenReturn(R.ok("RW-MT1", null));
        var first = service.create("PROCEDURE", 80L);
        assertEquals("DISPATCH_FAILED", first.getStatus());
        var retried = service.retry(first.getId());
        assertEquals(first.getId(), retried.getId());
        assertEquals("MES_CREATED", retried.getStatus());
        assertNull(
                db.queryForObject(
                        "SELECT last_error FROM mom_qms.qms_rework_order WHERE id=?",
                        String.class,
                        first.getId()));
        assertEquals(2, retried.getDispatchCount());
        assertEquals(1, count("qms_rework_order"));
    }

    @Test
    void concurrentCallbacksCreateOnlyOneChildWithOneHistoryAndEvent() throws Exception {
        var row = service.create("PROCEDURE", 80L);
        fault.holdChild = true;
        fault.orderReads.set(0);
        var auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first =
                    workers.submit(() -> scoped(auth, () -> service.completeFromMes(command(row))));
            assertTrue(fault.childHeld.await(3, TimeUnit.SECONDS));
            var second =
                    workers.submit(() -> scoped(auth, () -> service.completeFromMes(command(row))));
            assertTrue(fault.additionalOrderRead.await(3, TimeUnit.SECONDS));
            fault.releaseChild.countDown();
            assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertEquals(2, count("qms_inspection_procedure_task"));
            assertEquals(1, count("qms_inspection_procedure_history"));
            assertEquals(1, count("qms_inspection_event"));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT callback_count FROM mom_qms.qms_rework_order WHERE id=?",
                            Integer.class,
                            row.getId()));
        } finally {
            fault.releaseChild.countDown();
            workers.shutdownNow();
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"FIXED,5,2", "PENDING,2,5", "RANDOM,2,2"})
    void realProductSamplingCapsTheHeaderAndDetailsForTheReworkLot(
            String method, int configured, int originalRequested) {
        var standard = (QmsQualityStandardEntity) ReflectionTestUtils.getField(fixture, "standard");
        standard.setInspectionType("PRODUCT").setInspectionMethod(method).setNum(configured);
        db.update(
                "UPDATE mom_qms.qms_inspection_product_task SET inspection_num=? WHERE id=800",
                originalRequested);
        var order = service.create("PRODUCT", 81L);
        long id = assertDoesNotThrow(() -> service.completeFromMes(command(order)));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT inspection_num FROM mom_qms.qms_inspection_product_task WHERE id=?",
                        Integer.class,
                        id));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT total_num FROM mom_qms.qms_inspection_product_task_detail WHERE"
                                + " task_id=?",
                        Integer.class,
                        id));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT wait_num FROM mom_qms.qms_inspection_product_task_detail WHERE"
                                + " task_id=?",
                        Integer.class,
                        id));
        assertEquals(id, service.completeFromMes(command(order)));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "zeroHandled",
                "usableAboveHandled",
                "handledAboveTotal",
                "unavailableLessThanRework",
                "unknownHandled"
            })
    void contradictoryParentDispositionQuantitiesNeverDispatch(String damage) {
        switch (damage) {
            case "zeroHandled" ->
                    db.update(
                            "UPDATE mom_qms.qms_inspection_procedure_task SET handle_num=0 WHERE"
                                    + " id=700");
            case "usableAboveHandled" ->
                    db.update(
                            "UPDATE mom_qms.qms_inspection_procedure_task SET usable_num=3 WHERE"
                                    + " id=700");
            case "handledAboveTotal" ->
                    db.update(
                            "UPDATE mom_qms.qms_inspection_procedure_task SET handle_num=3 WHERE"
                                    + " id=700");
            case "unavailableLessThanRework" ->
                    db.update(
                            "UPDATE mom_qms.qms_inspection_procedure_task SET usable_num=1 WHERE"
                                    + " id=700");
            case "unknownHandled" ->
                    db.update(
                            "UPDATE mom_qms.qms_inspection_procedure_task SET handle_num=NULL WHERE"
                                    + " id=700");
        }
        assertThrows(RuntimeException.class, () -> service.create("PROCEDURE", 80L));
        assertEquals(0, count("qms_rework_order"));
        assertEquals(0, calls.get());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "QmsInspectionProductTaskMapper.insert",
                "QmsInspectionProductTaskMapper.updateById",
                "QmsInspectionProductTaskDetailMapper.insert"
            })
    void necessaryRealProductGenerationWritesCannotBeIgnored(String operation) {
        ((QmsQualityStandardEntity) ReflectionTestUtils.getField(fixture, "standard"))
                .setInspectionType("PRODUCT");
        var order = service.create("PRODUCT", 81L);
        fault.lose = operation;
        assertThrows(RuntimeException.class, () -> service.completeFromMes(command(order)));
        assertEquals(1, count("qms_inspection_product_task"));
        assertEquals(0, count("qms_inspection_product_task_detail"));
        assertNull(boundInspection(order));
        assertEquals(0, count("qms_inspection_event"));
    }

    @Test
    void anEmptyProductStandardCannotProduceABoundReinspection() {
        ((QmsQualityStandardEntity) ReflectionTestUtils.getField(fixture, "standard"))
                .setInspectionType("PRODUCT");
        var items = (QualityStandardItemService) ReflectionTestUtils.getField(fixture, "items");
        when(items.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of());
        var order = service.create("PRODUCT", 81L);
        assertThrows(RuntimeException.class, () -> service.completeFromMes(command(order)));
        assertEquals(1, count("qms_inspection_product_task"));
        assertNull(boundInspection(order));
    }

    @Test
    void aForeignFactoryProductStandardCannotBeUsedForAReinspection() {
        ((QmsQualityStandardEntity) ReflectionTestUtils.getField(fixture, "standard"))
                .setInspectionType("PRODUCT")
                .setFactoryCode("F2");
        var order = service.create("PRODUCT", 81L);
        assertThrows(RuntimeException.class, () -> service.completeFromMes(command(order)));
        assertEquals(1, count("qms_inspection_product_task"));
        assertNull(boundInspection(order));
    }

    private <T> T scoped(
            org.springframework.security.core.Authentication authentication, Callable<T> action)
            throws Exception {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(authentication);
        try {
            return action.call();
        } finally {
            TenantContextHolder.clear();
            FactoryContextHolder.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private int count(String table) {
        return db.queryForObject("SELECT COUNT(*) FROM mom_qms." + table, Integer.class);
    }

    private Long boundInspection(QmsReworkOrderEntity row) {
        return db.queryForObject(
                "SELECT reinspection_task_id FROM mom_qms.qms_rework_order WHERE id=?",
                Long.class,
                row.getId());
    }

    private QmsReworkCompletionDTO command(QmsReworkOrderEntity row) {
        var c = new QmsReworkCompletionDTO();
        c.setRequestId(row.getRequestId());
        c.setMesTaskCode("RW-MT1");
        c.setMesProduceTaskCode("RP1");
        c.setProcessCode("PROC");
        c.setProcessName("接线");
        c.setCompletedQuantity(new BigDecimal("2"));
        return c;
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
        int loseAt = 1;
        final AtomicInteger matches = new AtomicInteger(), orderReads = new AtomicInteger();
        boolean holdChild;
        final java.util.concurrent.atomic.AtomicBoolean held =
                new java.util.concurrent.atomic.AtomicBoolean();
        final CountDownLatch childHeld = new CountDownLatch(1),
                releaseChild = new CountDownLatch(1),
                additionalOrderRead = new CountDownLatch(1);

        public Object intercept(Invocation invocation) throws Throwable {
            var statement = (MappedStatement) invocation.getArgs()[0];
            if (holdChild
                    && statement.getId().endsWith("QmsReworkOrderMapper.selectList")
                    && orderReads.incrementAndGet() > 1) additionalOrderRead.countDown();
            var result = invocation.proceed();
            if (holdChild
                    && statement.getId().endsWith("QmsInspectionProcedureTaskMapper.insert")
                    && held.compareAndSet(false, true)) {
                childHeld.countDown();
                if (!releaseChild.await(5, TimeUnit.SECONDS))
                    throw new IllegalStateException("child latch timed out");
            }
            boolean lost =
                    lose != null
                            && (statement.getId().endsWith(lose)
                                    || lose.endsWith(".updateById")
                                            && statement
                                                    .getId()
                                                    .endsWith(
                                                            lose.replace(
                                                                    ".updateById", ".update")));
            return lost && matches.incrementAndGet() == loseAt ? 0 : result;
        }
    }
}
