package com.kitex.mom.qms.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesReworkInspectionDeliveryDTO;
import com.kitex.mom.mes.dto.MesReworkInspectionResultDTO;
import com.kitex.mom.qms.dto.resp.HandleFinishDTO;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.*;
import com.kitex.mom.qms.service.*;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** 真实QMS原件/协调单与Spring事务；仅MES远端端口受控，不连接运行库。 */
public class QmsReworkResultPublisherTransactionTest {
    private static final String DELIVERY_KEY = "test-only-rework-write-key-0123456789";
    private JdbcTemplate db;
    private JdbcDataSource dataSource;
    private final List<Integer> remotelyVisibleQueues = new ArrayList<>();
    private final List<String> remoteScopes = new ArrayList<>();
    private java.util.concurrent.CountDownLatch remoteEntered, remoteRelease;
    private QmsReworkResultPublisher publisher;
    private QmsReworkResultWorker worker;
    private QmsReworkResultDispatcher dispatcher;
    private QmsReworkResultOutboxMapper outbox;
    private boolean loseReceiptAfterRemote;
    private final List<MesReworkInspectionResultDTO> sent = new ArrayList<>();
    private final ReceiptFault fault = new ReceiptFault();
    private R<Void> response = R.ok();
    private boolean remoteThrows;
    private SqlSessionTemplate session;
    private DataSourceTransactionManager transactions;

    @BeforeEach
    void setup() throws Exception {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var ds = new JdbcDataSource();
        dataSource = ds;
        ds.setURL(
                "jdbc:h2:mem:qms_rework_"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(ds);
        db.execute("CREATE SCHEMA mom_qms");
        db.execute("CREATE TABLE qms_outbox_installation(version integer)");
        db.update("INSERT INTO qms_outbox_installation VALUES(1)");
        db.execute(
                "CREATE ALIAS mom_qms.mom_rework_result_outbox_ready_v1 FOR"
                    + " \"com.kitex.mom.qms.service.impl.QmsReworkResultPublisherTransactionTest.ready\"");
        var config = new MybatisConfiguration();
        var global = new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(
                new com.kitex.common.data.mybatis.MybatisPlusMetaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(config, global);
        for (var type :
                List.of(
                        QmsReworkResultOutboxEntity.class,
                        QmsReworkOrderEntity.class,
                        QmsInspectionProcedureTaskEntity.class,
                        QmsInspectionProductTaskEntity.class,
                        QmsInspectionProcedureHandleRecordEntity.class,
                        QmsInspectionProductHandleRecordEntity.class)) table(config, type);
        for (var type :
                List.of(
                        QmsReworkResultOutboxMapper.class,
                        QmsReworkOrderMapper.class,
                        QmsInspectionProcedureTaskMapper.class,
                        QmsInspectionProductTaskMapper.class,
                        QmsInspectionProcedureHandleRecordMapper.class,
                        QmsInspectionProductHandleRecordMapper.class)) config.addMapper(type);
        var tenant = new MybatisPlusInterceptor();
        tenant.addInnerInterceptor(
                new TenantLineInnerInterceptor(
                        new TenantLineHandler() {
                            @Override
                            public net.sf.jsqlparser.expression.Expression getTenantId() {
                                return new net.sf.jsqlparser.expression.LongValue(
                                        TenantContextHolder.getTenantId());
                            }
                        }));
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        factory.setGlobalConfig(global);
        factory.setPlugins(tenant, fault);
        session = new SqlSessionTemplate(factory.getObject());
        transactions = new DataSourceTransactionManager(ds);
        var remote = mock(RemoteMesTaskService.class);
        when(remote.deliverReworkInspection(any()))
                .thenAnswer(
                        call -> {
                            var snapshot = new MesReworkInspectionResultDTO();
                            MesReworkInspectionDeliveryDTO delivery = call.getArgument(0);
                            assertTrue(delivery.signatureMatches(DELIVERY_KEY));
                            var node =
                                    new com.fasterxml.jackson.databind.ObjectMapper()
                                            .readTree(delivery.getPayload());
                            snapshot =
                                    new com.fasterxml.jackson.databind.ObjectMapper()
                                            .treeToValue(
                                                    node.get("result"),
                                                    MesReworkInspectionResultDTO.class);
                            try (var connection = dataSource.getConnection();
                                    var statement = connection.createStatement();
                                    var rows =
                                            statement.executeQuery(
                                                    "SELECT COUNT(*) FROM"
                                                        + " mom_qms.qms_rework_result_outbox")) {
                                rows.next();
                                remotelyVisibleQueues.add(rows.getInt(1));
                            }
                            remoteScopes.add(
                                    TenantContextHolder.getTenantId()
                                            + ":"
                                            + FactoryContextHolder.getFactoryCode());
                            sent.add(snapshot);
                            if (remoteEntered != null) {
                                remoteEntered.countDown();
                                if (!remoteRelease.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                    throw new AssertionError("remote release timed out");
                            }
                            if (loseReceiptAfterRemote) fault.loseReceipt = true;
                            if (remoteThrows)
                                throw new IllegalStateException("MES response unknown");
                            return response;
                        });
        outbox = session.getMapper(QmsReworkResultOutboxMapper.class);
        worker =
                transactional(
                        new QmsReworkResultWorker(
                                outbox, session.getMapper(QmsReworkOrderMapper.class), remote),
                        QmsReworkResultWorker.class);
        ReflectionTestUtils.setField(worker, "deliverySecret", DELIVERY_KEY);
        dispatcher = new QmsReworkResultDispatcher(outbox, worker);
        var target =
                new QmsReworkResultPublisherImpl(
                        session.getMapper(QmsReworkOrderMapper.class), outbox, dispatcher);
        var proxy = new ProxyFactory(target);
        proxy.setInterfaces(QmsReworkResultPublisher.class);
        proxy.addAdvice(
                new TransactionInterceptor(
                        transactions, new AnnotationTransactionAttributeSource()));
        publisher = (QmsReworkResultPublisher) proxy.getProxy();
        db.update(
                "INSERT INTO"
                    + " mom_qms.qms_rework_order(id,tenant_id,factory_code,request_id,inspection_type,mes_task_code,reinspection_task_id,status,reinspection_status,result_sync_count,quantity)"
                    + " VALUES(10,1,'F1','RW-1','PROCEDURE','RW-T1',700,'REINSPECTION_PENDING','WAIT',0,5)");
        for (String type : List.of("PROCEDURE", "PRODUCT"))
            db.update(
                    "INSERT INTO "
                            + taskTable(type)
                            + "(id,tenant_id,factory_code,source_request_id,source_task_code,order_no,num,result,usable_num,handle_num)"
                            + " VALUES(700,1,'F1','REWORK-REINSPECTION:RW-1','P1','RW-T1',5,'QUALIFIED',5,5)");
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void aParentTransactionCannotSendBeforeItsQualityFactsCommit(String type) {
        type(type);
        new org.springframework.transaction.support.TransactionTemplate(transactions)
                .executeWithoutResult(
                        tx -> {
                            publisher.publish(type, 700L);
                            assertTrue(
                                    sent.isEmpty(),
                                    "MES must not observe uncommitted quality facts");
                        });
        payload(type, "QUALIFIED", 5, 5, 5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void aRolledBackParentDoesNotLeaveAnyRemoteDisposition(String type) {
        type(type);
        new org.springframework.transaction.support.TransactionTemplate(transactions)
                .executeWithoutResult(
                        tx -> {
                            publisher.publish(type, 700L);
                            tx.setRollbackOnly();
                        });
        assertTrue(sent.isEmpty(), "rolled-back quality must never be sent");
        state("REINSPECTION_PENDING", "WAIT", 0);
        assertEquals(0, queueCount());
    }

    @ParameterizedTest
    @CsvSource({
        "PROCEDURE,RELEASE,RELEASE",
        "PRODUCT,RELEASE,RELEASE",
        "PROCEDURE,CONCESSION,RECEIVE",
        "PRODUCT,CONCESSION,RECEIVE"
    })
    void acceptedTerminalDispositionsPublishTheRealHandlingResult(
            String type, String disposition, String result) throws Exception {
        prepareHandling(type);
        handle(type, 5, disposition, "DISPOSITION-1");
        payload(type, result, 5, 5, 5);
        assertEquals(disposition, sent.get(0).getHandleResult());
        state("CLOSED", "COMPLETED", 1);
        assertEquals("quality-worker", value("update_by", String.class));
        assertEquals(
                1, db.queryForObject("SELECT count(*) FROM " + handleTable(type), Integer.class));
    }

    @ParameterizedTest
    @CsvSource({
        "PROCEDURE,RELEASE,RELEASE",
        "PRODUCT,RELEASE,RELEASE",
        "PROCEDURE,CONCESSION,RECEIVE",
        "PRODUCT,CONCESSION,RECEIVE"
    })
    void aPartialDispositionCanAdvanceToItsAcceptedTerminalConclusion(
            String type, String disposition, String result) throws Exception {
        prepareHandling(type);
        handle(type, 2, disposition, "DISPOSITION-1");
        payload(type, "UN_QUALIFIED", 5, 2, 2);
        assertEquals(disposition, sent.get(0).getHandleResult());
        state("REINSPECTION_PENDING", "PARTIAL", 1);
        handle(type, 3, disposition, "DISPOSITION-2");
        state("CLOSED", "COMPLETED", 2);
        assertEquals(2, sent.size());
        var terminal = sent.get(1);
        assertEquals("RW-1", terminal.getRequestId());
        assertEquals(700L, terminal.getQmsTaskId());
        assertEquals(type, terminal.getInspectionType());
        assertEquals(result, terminal.getResult());
        assertEquals(disposition, terminal.getHandleResult());
        assertEquals(5, terminal.getTotalNum());
        assertEquals(5, terminal.getUsableNum());
        assertEquals(5, terminal.getHandleNum());
        assertEquals(
                2, db.queryForObject("SELECT count(*) FROM " + handleTable(type), Integer.class));
        assertEquals(
                5,
                db.queryForObject(
                        "SELECT sum(handle_num) FROM " + handleTable(type), Integer.class));
    }

    private void prepareHandling(String type) {
        type(type);
        quantities(type, 5, 0, 0);
        db.update("UPDATE " + taskTable(type) + " SET result='UN_QUALIFIED',status='UNHANDLED'");
        var user = mock(MomUser.class);
        when(user.getId()).thenReturn(2L);
        when(user.getName()).thenReturn("quality-worker");
        when(user.getUsername()).thenReturn("quality-worker");
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, "N/A", List.of()));
    }

    private String handleTable(String type) {
        return "PROCEDURE".equals(type)
                ? "qms_inspection_procedure_handle_record"
                : "qms_inspection_product_handle_record";
    }

    @ParameterizedTest
    @CsvSource({"PROCEDURE,5,5,CLOSED,COMPLETED", "PRODUCT,0,0,REINSPECTION_PENDING,PARTIAL"})
    void anExistingExemptionOnlyPublishesItsExplicitDisposition(
            String type, int usable, int handled, String status, String inspectionStatus)
            throws Exception {
        prepareHandling(type);
        db.update("UPDATE " + taskTable(type) + " SET result=NULL,status='WAIT'");
        Object service = inspectionService(type);
        if ("PROCEDURE".equals(type)) ((InspectionProcedureTaskService) service).exemption(700L);
        else ((InspectionProductTaskService) service).exemption(700L);
        publisher.retry(10L);
        payload(type, "EXEMPTION", 5, usable, handled);
        state(status, inspectionStatus, 1);
    }

    private Object inspectionService(String type) throws Exception {
        boolean procedure = "PROCEDURE".equals(type);
        var records =
                procedure
                        ? new InspectionProcedureHandleRecordServiceImpl()
                        : new InspectionProductHandleRecordServiceImpl();
        ReflectionTestUtils.setField(
                records,
                "baseMapper",
                procedure
                        ? session.getMapper(QmsInspectionProcedureHandleRecordMapper.class)
                        : session.getMapper(QmsInspectionProductHandleRecordMapper.class));
        var service =
                procedure
                        ? InspectionProcedureTaskServiceImpl.class
                        : InspectionProductTaskServiceImpl.class;
        var constructor = service.getDeclaredConstructors()[0];
        Object[] dependencies =
                Arrays.stream(constructor.getParameterTypes())
                        .map(
                                dependency -> {
                                    if (dependency == QmsReworkResultPublisher.class)
                                        return publisher;
                                    if (dependency == InspectionProcedureHandleRecordService.class
                                            || dependency
                                                    == InspectionProductHandleRecordService.class)
                                        return records;
                                    // 本轮不执行PLAN质量Outbox及未用到的检验资源；处置/原件/发送全部真实。
                                    return mock(dependency);
                                })
                        .toArray();
        Object target = constructor.newInstance(dependencies);
        ReflectionTestUtils.setField(
                target,
                "baseMapper",
                procedure
                        ? session.getMapper(QmsInspectionProcedureTaskMapper.class)
                        : session.getMapper(QmsInspectionProductTaskMapper.class));
        var proxy = new ProxyFactory(target);
        proxy.setInterfaces(
                procedure
                        ? InspectionProcedureTaskService.class
                        : InspectionProductTaskService.class);
        proxy.addAdvice(
                new TransactionInterceptor(
                        transactions, new AnnotationTransactionAttributeSource()));
        return proxy.getProxy();
    }

    private void handle(String type, int quantity, String result, String request) throws Exception {
        Object service = inspectionService(type);
        var command =
                new HandleFinishDTO()
                        .setId(700L)
                        .setHandleNum(quantity)
                        .setHandleResult(result)
                        .setRequestId(request)
                        .setHandleRemark("approved test disposition");
        if ("PROCEDURE".equals(type))
            ((InspectionProcedureTaskService) service).handleFinish(command);
        else ((InspectionProductTaskService) service).handleFinish(command);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void aValidQualifiedSourcePublishesItsActualLotAndSavesTheReceipt(String type) {
        type(type);
        publisher.publish(type.toLowerCase(Locale.ROOT), 700L);
        payload(type, "QUALIFIED", 5, 5, 5);
        state("CLOSED", "COMPLETED", 1);
        assertNotNull(value("result_sync_time", LocalDateTime.class));
    }

    @ParameterizedTest
    @CsvSource(
            value = {"PROCEDURE,0,0", "PRODUCT,0,0", "PROCEDURE,NULL,NULL", "PRODUCT,NULL,NULL"},
            nullValues = "NULL")
    void legacyDefaultDispositionRemainsCompatible(String type, Integer usable, Integer handled) {
        type(type);
        quantities(type, 5, usable, handled);
        publisher.retry(10L);
        payload(type, "QUALIFIED", 5, 5, 5);
        state("CLOSED", "COMPLETED", 1);
    }

    @ParameterizedTest
    @CsvSource({
        "PROCEDURE,2,3,PARTIAL,REINSPECTION_PENDING",
        "PRODUCT,2,3,PARTIAL,REINSPECTION_PENDING",
        "PROCEDURE,0,5,REJECTED,CLOSED",
        "PRODUCT,2,5,COMPLETED,CLOSED"
    })
    void anUnqualifiedResultKeepsItsActualCumulativeDisposition(
            String type, int usable, int handled, String inspectionStatus, String status) {
        type(type);
        quantities(type, 5, usable, handled);
        db.update("UPDATE " + taskTable(type) + " SET result='UN_QUALIFIED'");
        publisher.publish(type, 700L);
        payload(type, "UN_QUALIFIED", 5, usable, handled);
        state(status, inspectionStatus, 1);
    }

    @ParameterizedTest
    @CsvSource({
        "PROCEDURE,2,3",
        "PRODUCT,2,3",
        "PROCEDURE,2,2",
        "PRODUCT,0,5",
        "PROCEDURE,-1,0",
        "PRODUCT,0,-1",
        "PROCEDURE,3,2",
        "PRODUCT,1,6"
    })
    void aQualifiedConclusionCannotEraseExplicitContradictoryDisposition(
            String type, int usable, int handled) {
        type(type);
        quantities(type, 5, usable, handled);
        assertDoesNotThrow(() -> publisher.publish(type, 700L));
        failedWithoutSending();
    }

    @ParameterizedTest
    @CsvSource(
            value = {"PROCEDURE,0", "PRODUCT,-1", "PRODUCT,NULL"},
            nullValues = "NULL")
    void anInvalidInspectionLotCannotBeConfirmedAsSynchronized(String type, Integer total) {
        type(type);
        quantities(type, total, 0, 0);
        assertDoesNotThrow(() -> publisher.retry(10L));
        failedWithoutSending();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void aMissingSourcePersistsAnActionableFailureForTheOriginalOrder(String type) {
        type(type);
        db.update("DELETE FROM " + taskTable(type));
        assertDoesNotThrow(() -> publisher.retry(10L));
        failedWithoutSending();
    }

    @ParameterizedTest
    @CsvSource({
        "PROCEDURE,factory_code,F2",
        "PRODUCT,tenant_id,2",
        "PROCEDURE,source_request_id,OTHER",
        "PRODUCT,order_no,OTHER"
    })
    void anotherSourcesInspectionCannotBePublishedAsThisRework(
            String type, String field, String value) {
        type(type);
        db.update("UPDATE " + taskTable(type) + " SET " + field + "=?", value);
        assertDoesNotThrow(() -> publisher.retry(10L));
        failedWithoutSending();
    }

    @ParameterizedTest
    @CsvSource({"PROCEDURE,-1,0", "PRODUCT,0,-1", "PROCEDURE,3,2", "PRODUCT,1,6"})
    void anUnqualifiedConclusionCannotSendInvalidCumulativeQuantities(
            String type, int usable, int handled) {
        type(type);
        quantities(type, 5, usable, handled);
        db.update("UPDATE " + taskTable(type) + " SET result='UN_QUALIFIED'");
        assertDoesNotThrow(() -> publisher.retry(10L));
        failedWithoutSending();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCEDURE", "PRODUCT"})
    void onlyTheActualReinspectionLotIsSentWhenMesCompletedMore(String type) {
        type(type);
        db.update("UPDATE mom_qms.qms_rework_order SET quantity=2,mes_completed_quantity=5");
        quantities(type, 2, 2, 2);
        publisher.retry(10L);
        payload(type, "QUALIFIED", 2, 2, 2);
        state("CLOSED", "COMPLETED", 1);
    }

    @Test
    void successfulRetryActuallyClearsThePreviousPersistedError() {
        response = R.failed("MES temporarily unavailable");
        publisher.publish("PROCEDURE", 700L);
        state("RESULT_SYNC_FAILED", "SYNC_FAILED", 1);
        assertEquals("MES拒绝复检同步，请核对接收服务业务日志", value("last_error", String.class));
        response = R.ok();
        publisher.retry(10L);
        state("CLOSED", "COMPLETED", 2);
        assertNull(value("last_error", String.class));
        assertNotNull(value("result_sync_time", LocalDateTime.class));
        assertEquals(
                List.of("RW-1", "RW-1"),
                sent.stream().map(MesReworkInspectionResultDTO::getRequestId).toList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unknownOrAbsentRemoteResponseLeavesARetryableFailure(boolean throwsException) {
        remoteThrows = throwsException;
        response = null;
        publisher.retry(10L);
        state("RESULT_SYNC_FAILED", "SYNC_FAILED", 1);
        assertFalse(value("last_error", String.class).isBlank());
        assertNull(value("result_sync_time", LocalDateTime.class));
        payload("PROCEDURE", "QUALIFIED", 5, 5, 5);
    }

    @Test
    void aLostEnqueueReceiptRollsBackBeforeAnyRemoteSend() {
        fault.loseReceipt = true;
        assertThrows(IllegalStateException.class, () -> publisher.retry(10L));
        state("REINSPECTION_PENDING", "WAIT", 0);
        assertEquals(0, queueCount());
        assertTrue(sent.isEmpty());
        fault.loseReceipt = false;
        publisher.retry(10L);
        state("CLOSED", "COMPLETED", 1);
        payload("PROCEDURE", "QUALIFIED", 5, 5, 5);
    }

    @Test
    void aLostPostCommitReceiptRetainsTheFrozenEventForOriginalIdentityRecovery() {
        loseReceiptAfterRemote = true;
        assertDoesNotThrow(() -> publisher.publish("PROCEDURE", 700L));
        state("RESULT_SYNC_PENDING", "SYNC_PENDING", 0);
        assertEquals("PENDING", queue().getStatus());
        String original = queue().getPayload();
        Long eventId = queue().getId();
        assertEquals(1, sent.size());
        loseReceiptAfterRemote = false;
        fault.loseReceipt = false;
        publisher.retry(10L);
        state("CLOSED", "COMPLETED", 1);
        assertEquals("DELIVERED", queue().getStatus());
        assertEquals(eventId, queue().getId());
        assertEquals(original, queue().getPayload());
        assertEquals(2, sent.size());
        assertEquals(sent.get(0), sent.get(1));
        assertEquals(1, queueCount());
    }

    @Test
    void aNormalInspectionWithoutAReworkOrderDoesNotPublish() {
        publisher.publish("PROCEDURE", 701L);
        state("REINSPECTION_PENDING", "WAIT", 0);
        assertTrue(sent.isEmpty());
    }

    @Test
    void aSourceWithoutAConclusionDoesNotCreateAnAttempt() {
        db.update("UPDATE qms_inspection_procedure_task SET result=NULL");
        publisher.retry(10L);
        state("REINSPECTION_PENDING", "WAIT", 0);
        assertTrue(sent.isEmpty());
    }

    @Test
    void retryCannotMutateAnotherFactorysOrder() {
        FactoryContextHolder.setFactoryCode("F2");
        assertThrows(IllegalStateException.class, () -> publisher.retry(10L));
        state("REINSPECTION_PENDING", "WAIT", 0);
        assertTrue(sent.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"TENANT", "FACTORY", "TENANT_SKIP", "FACTORY_SKIP"})
    void missingOrBypassedScopeCannotPublish(String missing) {
        switch (missing) {
            case "TENANT" -> TenantContextHolder.clear();
            case "FACTORY" -> FactoryContextHolder.clear();
            case "TENANT_SKIP" -> TenantContextHolder.setTenantSkip();
            case "FACTORY_SKIP" -> FactoryContextHolder.setFactorySkip();
        }
        assertThrows(IllegalStateException.class, () -> publisher.publish("PROCEDURE", 700L));
        state("REINSPECTION_PENDING", "WAIT", 0);
        assertTrue(sent.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELED", "UNKNOWN"})
    void anUnsupportedConclusionCannotDriveReworkRelease(String result) {
        db.update("UPDATE qms_inspection_procedure_task SET result=?", result);
        assertDoesNotThrow(() -> publisher.retry(10L));
        failedWithoutSending();
    }

    @Test
    void aCommittedQueueIsVisibleToTheRemotePort() {
        response = R.failed();
        publisher.publish("PROCEDURE", 700L);
        assertEquals(1, queueCount());
        assertEquals(List.of(1), remotelyVisibleQueues);
        assertEquals("RETRY_WAIT", queue().getStatus());
        assertEquals(1, queue().getAttemptCount());
        assertTrue(queue().getNextAttemptTime().isAfter(LocalDateTime.now()));
        assertNull(queue().getDeliveredAt());
    }

    @Test
    void aDeliveredDuplicateDoesNotCreateAnotherEventOrRemoteSideEffect() {
        publisher.publish("PROCEDURE", 700L);
        String frozen = queue().getPayload();
        publisher.retry(10L);
        publisher.publish("PROCEDURE", 700L);
        assertEquals(1, queueCount());
        assertEquals(1, sent.size());
        assertEquals(frozen, queue().getPayload());
        state("CLOSED", "COMPLETED", 1);
    }

    @Test
    void aLaterDispositionCannotPassAnUnconfirmedEarlierResult() {
        db.update(
                "UPDATE "
                        + taskTable("PROCEDURE")
                        + " SET result='UN_QUALIFIED',usable_num=2,handle_num=2");
        response = R.failed();
        publisher.publish("PROCEDURE", 700L);
        Long first = queue().getId();
        String frozen = queue().getPayload();
        db.update(
                "UPDATE "
                        + taskTable("PROCEDURE")
                        + " SET result='RELEASE',usable_num=5,handle_num=5");
        response = R.ok();
        publisher.publish("PROCEDURE", 700L);
        assertEquals(1, sent.size());
        assertEquals(2, queueCount());
        var second =
                outbox.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                                        QmsReworkResultOutboxEntity>()
                                .eq(QmsReworkResultOutboxEntity::getSequenceNo, 2L));
        assertFalse(worker.deliver(second.getId()));
        publisher.retry(10L);
        assertEquals(2, sent.size());
        assertEquals(sent.get(0), sent.get(1));
        assertEquals(frozen, outbox.selectById(first).getPayload());
        state("RESULT_SYNC_PENDING", "SYNC_PENDING", 2);
        assertEquals(1, dispatcher.scan());
        assertEquals(3, sent.size());
        assertEquals(5, sent.get(2).getUsableNum());
        assertEquals("RELEASE", sent.get(2).getResult());
        state("CLOSED", "COMPLETED", 3);
    }

    @Test
    void eightUnconfirmedAttemptsStopAutomaticRetryButManualRetryKeepsTheSameEvent() {
        response = null;
        publisher.publish("PROCEDURE", 700L);
        Long id = queue().getId();
        String frozen = queue().getPayload();
        for (int i = 1; i < 8; i++) publisher.retry(10L);
        assertEquals("ATTENTION", queue().getStatus());
        assertEquals(8, queue().getAttemptCount());
        assertNull(queue().getNextAttemptTime());
        assertEquals(0, dispatcher.scan());
        response = R.ok();
        publisher.retry(10L);
        assertEquals("DELIVERED", queue().getStatus());
        assertEquals(9, queue().getAttemptCount());
        assertEquals(id, queue().getId());
        assertEquals(frozen, queue().getPayload());
    }

    @ParameterizedTest
    @ValueSource(strings = {"payload", "fingerprint", "order"})
    void corruptedOrReboundEvidenceCannotDriveRemoteRelease(String field) {
        response = R.failed();
        publisher.publish("PROCEDURE", 700L);
        if (field.equals("order"))
            db.update("UPDATE mom_qms.qms_rework_order SET request_id='REBOUND'");
        else db.update("UPDATE mom_qms.qms_rework_result_outbox SET " + field + "='changed'");
        db.update(
                "UPDATE mom_qms.qms_rework_result_outbox SET next_attempt_time=CURRENT_TIMESTAMP");
        response = R.ok();
        assertFalse(worker.deliver(queue().getId()));
        assertEquals(1, sent.size());
        assertEquals("ATTENTION", queue().getStatus());
        assertEquals(0, dispatcher.scan());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FACTORY", "TENANT", "TENANT_SKIP", "FACTORY_SKIP"})
    void aWorkerCannotUseAnotherOrBypassedScope(String change) {
        response = R.failed();
        publisher.publish("PROCEDURE", 700L);
        Long id = queue().getId();
        switch (change) {
            case "FACTORY" -> FactoryContextHolder.setFactoryCode("F2");
            case "TENANT" -> TenantContextHolder.setTenantId(2L);
            case "TENANT_SKIP" -> TenantContextHolder.setTenantSkip();
            case "FACTORY_SKIP" -> FactoryContextHolder.setFactorySkip();
        }
        if (change.endsWith("SKIP"))
            assertThrows(IllegalStateException.class, () -> worker.deliver(id));
        else assertFalse(worker.deliver(id));
        assertEquals(1, sent.size());
    }

    @Test
    void backgroundScanRestoresTheCallersScopeAndDoesNotTrustPublicShadows() {
        response = R.failed();
        publisher.publish("PROCEDURE", 700L);
        db.update(
                "UPDATE mom_qms.qms_rework_result_outbox SET next_attempt_time=CURRENT_TIMESTAMP");
        response = R.ok();
        TenantContextHolder.setTenantId(44L);
        TenantContextHolder.setTenantSkip();
        FactoryContextHolder.setFactoryCode("F44");
        FactoryContextHolder.setFactorySkip();
        assertEquals(1, dispatcher.scan());
        assertEquals(44L, TenantContextHolder.getTenantId());
        assertTrue(TenantContextHolder.getTenantSkip());
        assertEquals("F44", FactoryContextHolder.getFactoryCode());
        assertTrue(FactoryContextHolder.getFactorySkip());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM public.qms_rework_result_outbox", Integer.class));
        assertEquals(2, sent.size());
    }

    @Test
    void userRequestsCannotInvokeCrossTenantDiscovery() {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("test-user", "N/A"));
        assertThrows(IllegalStateException.class, () -> dispatcher.scan());
        SecurityContextHolder.clearContext();
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            assertThrows(IllegalStateException.class, () -> dispatcher.scan());
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void missingDeploymentRejectsTheParentInsteadOfLosingItsResult() {
        db.update("UPDATE qms_outbox_installation SET version=0");
        assertThrows(IllegalStateException.class, () -> publisher.publish("PROCEDURE", 700L));
        assertTrue(sent.isEmpty());
        assertEquals(0, queueCount());
        state("REINSPECTION_PENDING", "WAIT", 0);
    }

    @Test
    void anOutboxInsertFailureRollsBackTheOriginalBusinessWithoutSending() {
        fault.loseEnqueue = true;
        assertThrows(
                IllegalStateException.class,
                () ->
                        new org.springframework.transaction.support.TransactionTemplate(
                                        transactions)
                                .executeWithoutResult(
                                        tx -> {
                                            db.update(
                                                    "UPDATE mom_qms.qms_inspection_procedure_task"
                                                            + " SET status='FINISHED'");
                                            publisher.publish("PROCEDURE", 700L);
                                        }));
        assertNull(
                db.queryForObject(
                        "SELECT status FROM mom_qms.qms_inspection_procedure_task", String.class));
        assertEquals(0, queueCount());
        assertTrue(sent.isEmpty());
    }

    @Test
    void twoConcurrentWorkersOnlyConfirmTheOldestEventOnce() throws Exception {
        response = R.failed();
        publisher.publish("PROCEDURE", 700L);
        Long id = queue().getId();
        db.update(
                "UPDATE mom_qms.qms_rework_result_outbox SET next_attempt_time=CURRENT_TIMESTAMP");
        response = R.ok();
        remoteEntered = new java.util.concurrent.CountDownLatch(1);
        remoteRelease = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<Boolean> work =
                () -> {
                    TenantContextHolder.setTenantId(1L);
                    FactoryContextHolder.setFactoryCode("F1");
                    try {
                        return worker.deliver(id);
                    } finally {
                        TenantContextHolder.clear();
                        FactoryContextHolder.clear();
                    }
                };
        try {
            var first = pool.submit(work);
            assertTrue(remoteEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var second = pool.submit(work);
            remoteRelease.countDown();
            assertTrue(first.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(second.get(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            remoteRelease.countDown();
            pool.shutdownNow();
        }
        assertEquals(2, sent.size());
        assertEquals(2, queue().getAttemptCount());
        state("CLOSED", "COMPLETED", 2);
    }

    @Test
    void aFactoryBatchUsesEachCandidatesOwnTenantAndFactory() {
        response = R.failed();
        publisher.publish("PROCEDURE", 700L);
        db.update(
                "INSERT INTO"
                    + " mom_qms.qms_rework_order(id,tenant_id,factory_code,request_id,inspection_type,mes_task_code,reinspection_task_id,status,reinspection_status,result_sync_count,quantity)"
                    + " VALUES(20,2,'F2','RW-2','PROCEDURE','RW-T2',800,'REINSPECTION_PENDING','WAIT',0,3)");
        db.update(
                "INSERT INTO"
                    + " mom_qms.qms_inspection_procedure_task(id,tenant_id,factory_code,source_request_id,order_no,num,result,usable_num,handle_num)"
                    + " VALUES(800,2,'F2','REWORK-REINSPECTION:RW-2','RW-T2',3,'QUALIFIED',3,3)");
        TenantContextHolder.setTenantId(2L);
        FactoryContextHolder.setFactoryCode("F2");
        publisher.publish("PROCEDURE", 800L);
        db.update(
                "UPDATE mom_qms.qms_rework_result_outbox SET next_attempt_time=CURRENT_TIMESTAMP");
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        response = R.ok();
        assertEquals(2, dispatcher.scan());
        assertEquals(List.of("1:F1", "2:F2", "1:F1", "2:F2"), remoteScopes);
        assertNull(TenantContextHolder.getTenantId());
        assertNull(FactoryContextHolder.getFactoryCode());
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT COUNT(*) FROM mom_qms.qms_rework_result_outbox WHERE"
                                + " status='DELIVERED'",
                        Integer.class));
    }

    @Test
    void aBackgroundScanCannotReportSuccessAgainstAnUnreadyInstallation() {
        db.update("UPDATE qms_outbox_installation SET version=0");
        assertThrows(IllegalStateException.class, () -> dispatcher.scan());
        assertTrue(sent.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8})
    void aNewerDispositionPreservesTheOldestFailureAndItsManualRecovery(int attempts) {
        db.update(
                "UPDATE mom_qms.qms_inspection_procedure_task SET"
                        + " result='UN_QUALIFIED',usable_num=2,handle_num=2");
        response = R.failed();
        publisher.publish("PROCEDURE", 700L);
        for (int i = 1; i < attempts; i++) publisher.retry(10L);
        String error = value("last_error", String.class);
        db.update(
                "UPDATE mom_qms.qms_inspection_procedure_task SET"
                        + " result='RELEASE',usable_num=5,handle_num=5");
        publisher.publish("PROCEDURE", 700L);
        state("RESULT_SYNC_FAILED", "SYNC_FAILED", attempts);
        assertEquals(error, value("last_error", String.class));
        assertEquals(2, queueCount());
        assertEquals(attempts, sent.size());
        response = R.ok();
        publisher.retry(10L);
        assertEquals(1, dispatcher.scan());
        state("CLOSED", "COMPLETED", attempts + 2);
    }

    @Test
    void aTokenlessCompensationUsesTheDedicatedServiceAuthenticatedRoute() {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        RemoteMesTaskService client =
                feign.Feign.builder()
                        .contract(
                                new org.springframework.cloud.openfeign.support.SpringMvcContract())
                        .requestInterceptor(
                                new com.kitex.common.feign.interceptor
                                        .MomFeignInnerRequestInterceptor())
                        .requestInterceptor(
                                new com.kitex.common.security.feign.MomOAuthRequestInterceptor(
                                        request -> null))
                        .requestInterceptor(
                                new com.kitex.common.data.tenant.MomFeignTenantInterceptor())
                        .requestInterceptor(
                                new com.kitex.common.data.orgFilter.FeignFactoryCodeInterceptor())
                        .encoder(
                                (body, type, template) -> {
                                    try {
                                        template.body(json.writeValueAsString(body));
                                    } catch (Exception e) {
                                        throw new feign.codec.EncodeException(e.getMessage());
                                    }
                                })
                        .decoder((reply, type) -> R.ok())
                        .client(
                                (request, options) -> {
                                    String body =
                                            new String(
                                                    request.body(),
                                                    java.nio.charset.StandardCharsets.UTF_8);
                                    boolean accepted =
                                            request.url()
                                                            .endsWith(
                                                                    "/mesTask/internal/rework-inspection-delivery")
                                                    && request.headers()
                                                            .getOrDefault(
                                                                    com.kitex.common.core.constant
                                                                            .SecurityConstants.FROM,
                                                                    List.of())
                                                            .contains(
                                                                    com.kitex.common.core.constant
                                                                            .SecurityConstants
                                                                            .FROM_IN)
                                                    && request.headers()
                                                            .getOrDefault(
                                                                    com.kitex.common.core.constant
                                                                            .CommonConstants
                                                                            .TENANT_ID,
                                                                    List.of())
                                                            .contains("1")
                                                    && request.headers()
                                                            .getOrDefault(
                                                                    com.kitex.common.core.constant
                                                                            .CommonConstants
                                                                            .FACTORY_CODE,
                                                                    List.of())
                                                            .contains("F1")
                                                    && body.contains("\"signature\"")
                                                    && !body.contains("Bearer ");
                                    return feign.Response.builder()
                                            .request(request)
                                            .status(accepted ? 200 : 401)
                                            .reason(accepted ? "OK" : "Unauthorized")
                                            .headers(Map.of())
                                            .body("{}", java.nio.charset.StandardCharsets.UTF_8)
                                            .build();
                                })
                        .target(RemoteMesTaskService.class, "http://mes-service-test");
        worker =
                transactional(
                        new QmsReworkResultWorker(
                                outbox, session.getMapper(QmsReworkOrderMapper.class), client),
                        QmsReworkResultWorker.class);
        ReflectionTestUtils.setField(worker, "deliverySecret", DELIVERY_KEY);
        dispatcher = new QmsReworkResultDispatcher(outbox, worker);
        var target =
                new QmsReworkResultPublisherImpl(
                        session.getMapper(QmsReworkOrderMapper.class), outbox, dispatcher);
        var proxy = new ProxyFactory(target);
        proxy.setInterfaces(QmsReworkResultPublisher.class);
        proxy.addAdvice(
                new TransactionInterceptor(
                        transactions, new AnnotationTransactionAttributeSource()));
        publisher = (QmsReworkResultPublisher) proxy.getProxy();
        SecurityContextHolder.clearContext();
        publisher.publish("PROCEDURE", 700L);
        assertEquals(
                "DELIVERED",
                queue().getStatus(),
                "background sends must pass the service-authenticated receiver route");
    }

    @Test
    void aMissingWriteCredentialRetainsAnActionableOriginalWithoutSending() {
        ReflectionTestUtils.setField(worker, "deliverySecret", "");
        publisher.publish("PROCEDURE", 700L);
        assertEquals("ATTENTION", queue().getStatus());
        assertTrue(sent.isEmpty());
        assertTrue(value("last_error", String.class).contains("写密钥"));
        state("RESULT_SYNC_FAILED", "SYNC_FAILED", 1);
        ReflectionTestUtils.setField(worker, "deliverySecret", DELIVERY_KEY);
        publisher.retry(10L);
        assertEquals("DELIVERED", queue().getStatus());
        assertEquals(1, sent.size());
        assertEquals(1, queueCount());
    }

    private int queueCount() {
        return db.queryForObject(
                "SELECT COUNT(*) FROM mom_qms.qms_rework_result_outbox", Integer.class);
    }

    private QmsReworkResultOutboxEntity queue() {
        return outbox.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                                QmsReworkResultOutboxEntity>()
                        .orderByAsc(QmsReworkResultOutboxEntity::getSequenceNo)
                        .last("LIMIT 1"));
    }

    private <T> T transactional(T target, Class<T> type) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(
                        transactions, new AnnotationTransactionAttributeSource()));
        return type.cast(proxy.getProxy());
    }

    public static Integer ready(java.sql.Connection connection) throws java.sql.SQLException {
        try (var statement = connection.createStatement();
                var rows =
                        statement.executeQuery(
                                "SELECT version FROM public.qms_outbox_installation")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private void type(String type) {
        db.update("UPDATE mom_qms.qms_rework_order SET inspection_type=?", type);
    }

    private void quantities(String type, Integer total, Integer usable, Integer handled) {
        db.update(
                "UPDATE " + taskTable(type) + " SET num=?,usable_num=?,handle_num=?",
                total,
                usable,
                handled);
    }

    private String taskTable(String type) {
        return "PROCEDURE".equals(type)
                ? "qms_inspection_procedure_task"
                : "qms_inspection_product_task";
    }

    private <T> T value(String field, Class<T> type) {
        return db.queryForObject(
                "SELECT " + field + " FROM mom_qms.qms_rework_order WHERE id=10", type);
    }

    private void state(String status, String inspection, int attempts) {
        assertEquals(status, value("status", String.class));
        assertEquals(inspection, value("reinspection_status", String.class));
        assertEquals(attempts, value("result_sync_count", Integer.class));
    }

    private void failedWithoutSending() {
        state("RESULT_SYNC_FAILED", "SYNC_FAILED", 1);
        assertFalse(value("last_error", String.class).isBlank());
        assertNull(value("result_sync_time", LocalDateTime.class));
        assertTrue(sent.isEmpty());
    }

    private void payload(String type, String result, int total, int usable, int handled) {
        assertEquals(1, sent.size());
        var actual = sent.get(0);
        assertEquals("RW-1", actual.getRequestId());
        assertEquals(700L, actual.getQmsTaskId());
        assertEquals(type, actual.getInspectionType());
        assertEquals(result, actual.getResult());
        assertEquals(total, actual.getTotalNum());
        assertEquals(usable, actual.getUsableNum());
        assertEquals(handled, actual.getHandleNum());
    }

    private void table(MybatisConfiguration config, Class<?> type) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(config, "qms-rework-ddl"), type);
        var info = TableInfoHelper.getTableInfo(type);
        String canonical =
                info.getTableName().contains(".")
                        ? info.getTableName()
                        : "mom_qms." + info.getTableName();
        var ddl =
                new StringBuilder("CREATE TABLE ")
                        .append(canonical)
                        .append("(id bigint primary key");
        for (var field : info.getFieldList()) {
            var t = field.getPropertyType();
            String sql =
                    t == Long.class
                            ? "bigint"
                            : t == Integer.class
                                    ? "integer"
                                    : t == BigDecimal.class
                                            ? "decimal(24,8)"
                                            : t == LocalDateTime.class || t == Date.class
                                                    ? "timestamp"
                                                    : "varchar(64000)";
            ddl.append(',').append(field.getColumn()).append(' ').append(sql);
            if (field.getColumn().equals("del_flag")) ddl.append(" default '0'");
        }
        db.execute(ddl.append(')').toString());
        String shortName = canonical.substring("mom_qms.".length());
        if (type == QmsReworkResultOutboxEntity.class)
            db.execute(
                    "CREATE TABLE public."
                            + shortName
                            + " AS SELECT * FROM "
                            + canonical
                            + " WHERE 1=0");
        else db.execute("CREATE SYNONYM public." + shortName + " FOR " + canonical);
    }

    @Intercepts(
            @Signature(
                    type = Executor.class,
                    method = "update",
                    args = {MappedStatement.class, Object.class}))
    static class ReceiptFault implements Interceptor {
        boolean loseReceipt;
        boolean loseEnqueue;

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            var statement = (MappedStatement) invocation.getArgs()[0];
            if (loseEnqueue
                    && statement
                            .getId()
                            .equals(QmsReworkResultOutboxMapper.class.getName() + ".insert"))
                return 0;
            if (loseReceipt
                    && statement.getId().startsWith(QmsReworkOrderMapper.class.getName() + "."))
                return 0;
            return invocation.proceed();
        }
    }
}
