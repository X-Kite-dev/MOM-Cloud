package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.*;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** 真实质量同步、MES本地库存/证明/计数；业务锁以真实SQL锁替代PG advisory，远端来源计划受控。 */
class MesQualityQuantityTransactionTest {
    private JdbcTemplate db;
    private SqlSessionTemplate sessions;
    private DataSourceTransactionManager transactions;
    private MesQualityQuantityService quality;
    private MesTaskService tasks;
    private final Map<Class<?>, Object> dependencies = new HashMap<>();
    private final QueryObserver observer = new QueryObserver();
    private final CountDownLatch boundary = new CountDownLatch(1);
    private final CountDownLatch continueQuality = new CountDownLatch(1);
    private boolean competing;
    private String observedMapper;
    private UsernamePasswordAuthenticationToken authentication;

    @BeforeEach
    void setup() throws Exception {
        var user = mock(MomUser.class);
        when(user.getId()).thenReturn(2L);
        when(user.getTenantId()).thenReturn(1L);
        when(user.getUsername()).thenReturn("quality-worker");
        when(user.getName()).thenReturn("quality-worker");
        authentication = new UsernamePasswordAuthenticationToken(user, "N/A", List.of());
        context();
        var ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:quality_"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=500");
        db = new JdbcTemplate(ds);
        transactions = new DataSourceTransactionManager(ds);
        db.execute("CREATE TABLE business_lock(task_code varchar primary key)");
        db.update("INSERT INTO business_lock VALUES('T1')");
        var config = new MybatisConfiguration();
        var global = new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(
                new com.kitex.common.data.mybatis.MybatisPlusMetaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(config, global);
        for (var type :
                List.of(
                        MesTaskEntity.class,
                        MesProduceTaskEntity.class,
                        MesTaskArrangeEntity.class,
                        MesTaskReportEntity.class,
                        MesTaskReportRecordEntity.class,
                        MesWarehouseLiveStockEntity.class,
                        MesQualityStockProofEntity.class)) table(config, type);
        for (var type :
                List.of(
                        MesTaskMapper.class,
                        MesProduceTaskMapper.class,
                        MesTaskArrangeMapper.class,
                        MesTaskReportMapper.class,
                        MesTaskReportRecordMapper.class,
                        MesWarehouseLiveStockMapper.class,
                        MesQualityStockProofMapper.class)) config.addMapper(type);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        factory.setGlobalConfig(global);
        var interceptors = new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
        interceptors.addInnerInterceptor(
                new com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor(
                        new com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler() {
                            @Override
                            public net.sf.jsqlparser.expression.Expression getTenantId() {
                                return new net.sf.jsqlparser.expression.LongValue(
                                        TenantContextHolder.getTenantId());
                            }
                        }));
        observer.afterQuery =
                key -> {
                    if (competing
                            && Thread.currentThread().getName().equals("quality-sync")
                            && key.equals(observedMapper)) {
                        boundary.countDown();
                        await(continueQuality);
                    }
                };
        factory.setPlugins(interceptors, observer);
        sessions = new SqlSessionTemplate(factory.getObject());
        for (var type :
                List.of(
                        MesTaskMapper.class,
                        MesProduceTaskMapper.class,
                        MesTaskArrangeMapper.class,
                        MesTaskReportMapper.class,
                        MesTaskReportRecordMapper.class,
                        MesWarehouseLiveStockMapper.class,
                        MesQualityStockProofMapper.class))
            dependencies.put(type, sessions.getMapper(type));
        // 只有晚期故障用例拦截insert；其余审计读写全部委托真实MyBatis。
        dependencies.put(
                MesTaskReportRecordMapper.class,
                mock(
                        MesTaskReportRecordMapper.class,
                        org.mockito.AdditionalAnswers.delegatesTo(
                                sessions.getMapper(MesTaskReportRecordMapper.class))));
        var groups = dependency(MesIdentifierBindingGroupService.class);
        doAnswer(
                        call -> {
                            if (competing
                                    && Thread.currentThread().getName().equals("quality-sync"))
                                boundary.countDown();
                            String taskCode = call.getArgument(0, String.class);
                            db.queryForObject(
                                    "SELECT task_code FROM business_lock WHERE task_code=? FOR"
                                            + " UPDATE",
                                    String.class,
                                    taskCode);
                            return null;
                        })
                .when(groups)
                .lockDispatchTask(anyString());
        quality = transactional(construct(MesQualityQuantityServiceImpl.class));
        dependencies.put(MesQualityQuantityService.class, quality);
        store(MesProduceTaskService.class, MesProduceTaskEntity.class, MesProduceTaskMapper.class);
        var actualTasks = construct(MesTaskServiceImpl.class);
        ReflectionTestUtils.setField(
                actualTasks, "baseMapper", sessions.getMapper(MesTaskMapper.class));
        tasks = transactional(actualTasks);
        db.update(
                "INSERT INTO"
                    + " mes_task(id,tenant_id,factory_code,code,status,notice_num,complete_num,un_in_stock_num)"
                    + " VALUES(1,1,'F1','T1','PRODUCT',5,5,0)");
        db.update(
                "INSERT INTO"
                    + " mes_produce_task(id,tenant_id,factory_code,code,task_code,route_code,process_route_code,status,last_route,notice_num,complete_num,quality_released_num)"
                    + " VALUES(21,1,'F1','P1','T1','R1','ROUTE-1','PRODUCT',true,5,5,0)");
        db.update(
                "INSERT INTO"
                    + " mes_task_arrange(id,tenant_id,factory_code,code,task_code,produce_task_code,route_code,status,last_route,notice_num,complete_num,quality_released_num)"
                    + " VALUES(11,1,'F1','A1','T1','P1','R1','PRODUCT',true,5,5,0)");
        db.update(
                "INSERT INTO"
                    + " mes_task_report(id,tenant_id,factory_code,task_code,produce_task_code,arrange_code,report_num,quality_released_num,quality_pending_num,quality_rejected_num,quality_quantity_status,status,report_time)"
                    + " VALUES(100,1,'F1','T1','P1','A1',5,0,5,0,'PENDING','EFFECTIVE',CURRENT_TIMESTAMP)");
        db.update(
                "INSERT INTO"
                    + " mes_task_report_record(id,tenant_id,factory_code,report_id,type,quality_released_num,quality_pending_num,quality_rejected_num)"
                    + " VALUES(101,1,'F1',100,'REPORT',0,5,0)");
        db.update(
                "INSERT INTO"
                    + " mes_warehouse_live_stock(id,tenant_id,factory_code,source_report_id,mat_code,mat_unit,num,inspection_status,status)"
                    + " VALUES(201,1,'F1',100,'MAT1','PCS',5,'PENDING','1')");
    }

    @Test
    void aFullReleaseUpdatesItsRealSourceAndRepeatingItDoesNotCountAgain() {
        quality.reconcile("P1", passed());
        quality.reconcile("P1", passed());
        quantity("mes_task_report", "quality_released_num", 5);
        quantity("mes_task_report", "quality_pending_num", 0);
        quantity("mes_task_arrange", "quality_released_num", 5);
        quantity("mes_produce_task", "quality_released_num", 5);
        quantity("mes_task", "un_in_stock_num", 5);
        assertEquals(
                "RELEASED",
                db.queryForObject(
                        "SELECT inspection_status FROM mes_warehouse_live_stock", String.class));
        assertEquals(1, count("mes_quality_stock_proof"));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM mes_task_report_record WHERE type='QUALITY_SYNC'",
                        Integer.class));
    }

    @Test
    void partialReleaseAndRejectionSplitStockAndKeepTheRemainingPendingQuantity() {
        var item =
                new MesQualityGateItemDTO()
                        .setInspectionId(700L)
                        .setProduceTaskCode("P1")
                        .setQmsTotalNum(5)
                        .setQmsUsableNum(2)
                        .setQmsHandleNum(3);
        var decision = passed().setPassed(false).setItems(List.of(item));
        quality.reconcile("P1", decision);
        quality.reconcile("P1", decision);
        quantity("mes_task_report", "quality_released_num", 2);
        quantity("mes_task_report", "quality_pending_num", 2);
        quantity("mes_task_report", "quality_rejected_num", 1);
        quantity("mes_task", "un_in_stock_num", 2);
        assertEquals(3, count("mes_warehouse_live_stock"));
        assertEquals(2, count("mes_quality_stock_proof"));
        for (var entry : Map.of("PENDING", 2, "RELEASED", 2, "REJECTED", 1).entrySet())
            assertEquals(
                    0,
                    BigDecimal.valueOf(entry.getValue())
                            .compareTo(
                                    db.queryForObject(
                                            "SELECT num FROM mes_warehouse_live_stock WHERE"
                                                    + " inspection_status=?",
                                            BigDecimal.class,
                                            entry.getKey())));
    }

    @Test
    void aFinishedProcessCanReleaseItsHistoricalPendingOutputWithoutReopeningProduction() {
        db.update("UPDATE mes_produce_task SET status='FINISH'");
        db.update("UPDATE mes_task SET status='FINISH'");
        quality.reconcile("P1", passed());
        assertNotNull(
                db.queryForObject(
                        "SELECT transfer_released_time FROM mes_produce_task",
                        LocalDateTime.class));
        assertEquals("FINISH", db.queryForObject("SELECT status FROM mes_task", String.class));
        quantity("mes_task", "un_in_stock_num", 5);
    }

    @Test
    void anotherFactoryUsingTheSameProcessCodeDoesNotPreventReleaseOfThisActualSource() {
        db.update(
                "INSERT INTO"
                    + " mes_produce_task(id,tenant_id,factory_code,code,task_code,route_code,status,quality_released_num)"
                    + " VALUES(22,1,'F2','P1','T2','R2','CLOSE',99)");
        assertDoesNotThrow(() -> quality.reconcile("P1", passed()));
        assertEquals(
                0,
                new BigDecimal("99")
                        .compareTo(
                                db.queryForObject(
                                        "SELECT quality_released_num FROM mes_produce_task WHERE"
                                                + " id=22",
                                        BigDecimal.class)));
        quantity("mes_task", "un_in_stock_num", 5);
    }

    @Test
    void aNullHistoricalProcessReleaseCanBeAdvancedFromZero() {
        db.update("UPDATE mes_produce_task SET quality_released_num=NULL");
        assertDoesNotThrow(() -> quality.reconcile("P1", passed()));
        quantity("mes_produce_task", "quality_released_num", 5);
    }

    @Test
    void aReportCannotReleaseThroughAnotherActualTasksDispatch() {
        db.update(
                "INSERT INTO"
                    + " mes_task_arrange(id,tenant_id,factory_code,code,task_code,produce_task_code,route_code,last_route,complete_num,quality_released_num)"
                    + " VALUES(12,1,'F1','A2','T2','P2','R2',true,5,0)");
        db.update("UPDATE mes_task_report SET arrange_code='A2'");
        assertThrows(CheckedException.class, () -> quality.reconcile("P1", passed()));
        unchanged();
    }

    @Test
    void aReportsWrongRootMustBeRejectedBeforeAnyQuantityOrStockChange() {
        db.update(
                "INSERT INTO mes_task(id,tenant_id,factory_code,code,status,un_in_stock_num)"
                        + " VALUES(2,1,'F1','T2','PRODUCT',0)");
        db.update("UPDATE mes_task_report SET task_code='T2'");
        assertThrows(CheckedException.class, () -> quality.reconcile("P1", passed()));
        unchanged();
    }

    @Test
    void anUnknownProcessCannotBeReportedAsSuccessfullySynchronizedJustBecauseItHasNoReports() {
        assertThrows(CheckedException.class, () -> quality.reconcile("MISSING", passed()));
        unchanged();
    }

    @Test
    void anAmbiguousActualProcessCodeIsABusinessRejectionWithNoEffects() {
        db.update(
                "INSERT INTO"
                    + " mes_produce_task(id,tenant_id,factory_code,code,task_code,route_code,status)"
                    + " VALUES(22,1,'F1','P1','T2','R2','PRODUCT')");
        assertThrows(CheckedException.class, () -> quality.reconcile("P1", passed()));
        unchanged();
    }

    @Test
    void anAuditFailureRollsBackStockProofsAndAllReleasedCounters() {
        doReturn(0)
                .when((MesTaskReportRecordMapper) dependencies.get(MesTaskReportRecordMapper.class))
                .insert(any(MesTaskReportRecordEntity.class));
        assertThrows(CheckedException.class, () -> quality.reconcile("P1", passed()));
        unchanged();
    }

    @Test
    void productionHoldingItsTaskLockCanStillLockReportsWhileIndependentQualitySyncWaits()
            throws Exception {
        compete(
                "MesTaskReportMapper",
                "mes_task_report",
                true,
                () -> quality.reconcile("P1", passed()));
        quantity("mes_task", "un_in_stock_num", 5);
    }

    @Test
    void reworkCallbackReleasesTheSameHistoricalOutputOnlyOnce() {
        rework();
        tasks.syncReworkInspection(reworkResult());
        tasks.syncReworkInspection(reworkResult());
        quantity("mes_task", "rework_qualified_num", 5);
        quantity("mes_task", "un_in_stock_num", 5);
        assertEquals(1, count("mes_quality_stock_proof"));
        assertEquals(
                "FINISH",
                db.queryForObject("SELECT status FROM mes_task WHERE id=1", String.class));
    }

    @Test
    void productionHoldingTheBusinessLockCanStillLockItsRootWhileReinspectionWaits()
            throws Exception {
        rework();
        compete(
                "MesTaskMapper",
                "mes_task",
                false,
                () -> tasks.syncReworkInspection(reworkResult()));
        quantity("mes_task", "rework_qualified_num", 5);
    }

    @Test
    void reinspectionCannotChooseOneOfTwoRootsForTheSameActualRequest() {
        rework();
        db.update(
                "INSERT INTO"
                    + " mes_task(id,tenant_id,factory_code,code,status,resource_type,rework_request_id,reinspection_task_id)"
                    + " VALUES(3,1,'F1','T2','FINISH','REWORK','RW-1',700)");
        assertThrows(CheckedException.class, () -> tasks.syncReworkInspection(reworkResult()));
        unchanged();
    }

    private void rework() {
        db.update(
                "UPDATE mes_task SET"
                    + " resource_type='REWORK',status='FINISH',rework_request_id='RW-1',reinspection_task_id=700,source_task_code='T0',rework_qualified_num=0,rework_rejected_num=0"
                    + " WHERE id=1");
        db.update("UPDATE mes_produce_task SET status='FINISH' WHERE id=21");
        db.update(
                "INSERT INTO mes_task(id,tenant_id,factory_code,code,status,un_in_stock_num)"
                        + " VALUES(2,1,'F1','T0','FINISH',0)");
    }

    private MesReworkInspectionResultDTO reworkResult() {
        return new MesReworkInspectionResultDTO()
                .setRequestId("RW-1")
                .setQmsTaskId(700L)
                .setResult("QUALIFIED")
                .setTotalNum(5)
                .setUsableNum(5)
                .setHandleNum(5);
    }

    @Test
    void aQualifiedReinspectionOfOnlyTwoPiecesLeavesTheOtherThreeInQuarantine() {
        rework();
        var result = reworkResult().setTotalNum(2).setUsableNum(2).setHandleNum(2);
        tasks.syncReworkInspection(result);
        tasks.syncReworkInspection(result);
        reworkQuantities(2, 0, 3, 1);
        assertEquals(
                "PARTIAL",
                db.queryForObject(
                        "SELECT reinspection_status FROM mes_task WHERE id=1", String.class));
        assertEquals(
                0,
                new BigDecimal("3")
                        .compareTo(
                                db.queryForObject(
                                        "SELECT sum(num) FROM mes_warehouse_live_stock WHERE"
                                                + " inspection_status='PENDING'",
                                        BigDecimal.class)));
    }

    @Test
    void aLaterLargerQualifiedCumulativeResultReleasesOnlyTheNewlyCertifiedQuantity() {
        rework();
        tasks.syncReworkInspection(reworkResult().setTotalNum(2).setUsableNum(2).setHandleNum(2));
        reworkQuantities(2, 0, 3, 1);
        tasks.syncReworkInspection(reworkResult());
        tasks.syncReworkInspection(reworkResult());
        reworkQuantities(5, 0, 0, 2);
        assertEquals(
                "RELEASED",
                db.queryForObject(
                        "SELECT reinspection_status FROM mes_task WHERE id=1", String.class));
    }

    @Test
    void ordinaryQualitySyncCannotBypassAReworkOrdersRegisteredReinspection() {
        rework();
        assertThrows(CheckedException.class, () -> quality.reconcile("P1", passed()));
        unchanged();
    }

    @Test
    void aPassedOrdinarySamplingGateStillReleasesItsWholeProductionLot() {
        var item =
                new MesQualityGateItemDTO()
                        .setInspectionId(700L)
                        .setProduceTaskCode("P1")
                        .setQmsTotalNum(5)
                        .setQmsUsableNum(2)
                        .setQmsHandleNum(2)
                        .setGateStatus("PASSED");
        quality.reconcile("P1", passed().setGateMode("BLOCK").setItems(List.of(item)));
        quantity("mes_task_report", "quality_released_num", 5);
        quantity("mes_task", "un_in_stock_num", 5);
        assertEquals(1, count("mes_quality_stock_proof"));
    }

    @Test
    void aQualifiedResultWithLegacyZeroDispositionFieldsStillUsesItsDeclaredInspectionTotal() {
        rework();
        tasks.syncReworkInspection(reworkResult().setUsableNum(0).setHandleNum(0));
        reworkQuantities(5, 0, 0, 1);
    }

    @ParameterizedTest
    @CsvSource({"-1,0", "0,-1", "3,2", "1,0"})
    void qualifiedStatusDoesNotEraseExplicitInvalidDispositionNumbers(int usable, int handled) {
        rework();
        assertThrows(
                CheckedException.class,
                () ->
                        tasks.syncReworkInspection(
                                reworkResult().setUsableNum(usable).setHandleNum(handled)));
        unchanged();
    }

    @ParameterizedTest
    @CsvSource({"2,3", "2,2", "0,5"})
    void qualifiedStatusDoesNotExpandExplicitPartialDispositionToTheFullInspectionLot(
            int usable, int handled) {
        rework();
        assertThrows(
                CheckedException.class,
                () ->
                        tasks.syncReworkInspection(
                                reworkResult().setUsableNum(usable).setHandleNum(handled)));
        unchanged();
        reworkQuantities(0, 0, 5, 0);
        quantity("mes_produce_task", "quality_released_num", 0);
    }

    @ParameterizedTest
    @CsvSource({"1,3", "3,3"})
    void aNewCumulativeResultCannotDecreaseEitherReleasedOrRejectedQuantity(
            int usable, int handled) {
        rework();
        tasks.syncReworkInspection(
                reworkResult().setResult("UNQUALIFIED").setUsableNum(2).setHandleNum(3));
        reworkQuantities(2, 1, 2, 2);
        assertThrows(
                CheckedException.class,
                () ->
                        tasks.syncReworkInspection(
                                reworkResult()
                                        .setResult("UNQUALIFIED")
                                        .setUsableNum(usable)
                                        .setHandleNum(handled)));
        reworkQuantities(2, 1, 2, 2);
    }

    @ParameterizedTest
    @CsvSource({"6,6,true", "4,6,false"})
    void requestedDispositionBeyondActualTrackedOutputCannotBeClampedIntoFalseSuccess(
            int usable, int handled, boolean allQualified) {
        rework();
        assertThrows(
                CheckedException.class,
                () ->
                        quality.reconcileRework(
                                "P1",
                                700L,
                                BigDecimal.valueOf(usable),
                                BigDecimal.valueOf(handled),
                                allQualified));
        unchanged();
    }

    @Test
    void reinspectionWithoutAnyOriginalOutputReportsCannotBecomeReleased() {
        rework();
        db.update("DELETE FROM mes_task_report_record");
        db.update("DELETE FROM mes_task_report");
        db.update("DELETE FROM mes_warehouse_live_stock");
        assertThrows(CheckedException.class, () -> tasks.syncReworkInspection(reworkResult()));
        quantity("mes_task", "rework_qualified_num", 0);
        assertNull(
                db.queryForObject(
                        "SELECT reinspection_status FROM mes_task WHERE id=1", String.class));
        assertEquals(0, count("mes_quality_stock_proof"));
    }

    @Test
    void missingQualityQuantitySourcesCannotMakeAnUntrackedRemainderLookReleased() {
        rework();
        db.update("UPDATE mes_task_report SET report_num=3,quality_pending_num=3 WHERE id=100");
        db.update("UPDATE mes_warehouse_live_stock SET num=3 WHERE id=201");
        assertThrows(
                CheckedException.class,
                () ->
                        tasks.syncReworkInspection(
                                reworkResult().setTotalNum(3).setUsableNum(3).setHandleNum(3)));
        quantity("mes_task", "un_in_stock_num", 0);
        assertEquals(0, count("mes_quality_stock_proof"));
    }

    @ParameterizedTest
    @CsvSource({"quality_pending_num,-1", "quality_released_num,-1", "quality_released_num,2"})
    void invalidOriginalQualityQuantitiesAreRejectedRatherThanHiddenByCapacityClamping(
            String column, int value) {
        rework();
        db.update("UPDATE mes_task_report SET " + column + "=? WHERE id=100", value);
        assertThrows(CheckedException.class, () -> tasks.syncReworkInspection(reworkResult()));
        quantity("mes_task", "un_in_stock_num", 0);
        assertEquals(0, count("mes_quality_stock_proof"));
        assertEquals(
                "PENDING",
                db.queryForObject(
                        "SELECT inspection_status FROM mes_warehouse_live_stock", String.class));
    }

    @Test
    void aReworkQuantitySyncMustMatchTheActuallyRegisteredQmsTask() {
        rework();
        assertThrows(
                CheckedException.class,
                () ->
                        quality.reconcileRework(
                                "P1", 701L, new BigDecimal("2"), new BigDecimal("2"), false));
        unchanged();
    }

    @Test
    void anAllQualifiedReinspectionCannotAlsoDeclareRejectedPieces() {
        rework();
        assertThrows(
                CheckedException.class,
                () ->
                        quality.reconcileRework(
                                "P1", 700L, new BigDecimal("2"), new BigDecimal("3"), true));
        unchanged();
    }

    @Test
    void finalReinspectionCannotReleaseAnIntermediateProcessUsingTheSameRootRegistration() {
        rework();
        db.update("UPDATE mes_produce_task SET last_route=false WHERE id=21");
        db.update("UPDATE mes_task_arrange SET last_route=false WHERE id=11");
        assertThrows(
                CheckedException.class,
                () ->
                        quality.reconcileRework(
                                "P1", 700L, new BigDecimal("2"), new BigDecimal("2"), false));
        unchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"mes_produce_task", "mes_task_arrange"})
    void releasedReportQuantitiesCannotBeAcknowledgedWhenTheirParentCountersDisagree(
            String staleTable) {
        rework();
        db.update(
                "UPDATE mes_task_report SET"
                    + " quality_released_num=2,quality_pending_num=3,quality_quantity_status='PARTIAL'"
                    + " WHERE id=100");
        db.update("UPDATE mes_produce_task SET quality_released_num=2 WHERE id=21");
        db.update("UPDATE mes_task_arrange SET quality_released_num=2 WHERE id=11");
        db.update("UPDATE " + staleTable + " SET quality_released_num=0");
        assertThrows(CheckedException.class, () -> tasks.syncReworkInspection(reworkResult()));
        quantity("mes_task", "un_in_stock_num", 0);
        assertEquals(0, count("mes_quality_stock_proof"));
    }

    @Test
    void theReworkQuantityEntryCannotBeUsedForOrdinaryProduction() {
        assertThrows(
                CheckedException.class,
                () ->
                        quality.reconcileRework(
                                "P1", 700L, new BigDecimal("2"), new BigDecimal("2"), false));
        unchanged();
    }

    @Test
    void aReworkOrderWithoutItsRegisteredReinspectionCannotReleaseOutput() {
        rework();
        db.update("UPDATE mes_task SET reinspection_task_id=NULL WHERE id=1");
        assertThrows(
                CheckedException.class,
                () ->
                        quality.reconcileRework(
                                "P1", 700L, new BigDecimal("2"), new BigDecimal("2"), false));
        unchanged();
    }

    @Test
    void unsupportedFractionalQmsCountsAreARejectedBusinessInputWithoutRounding() {
        rework();
        assertThrows(
                CheckedException.class,
                () ->
                        quality.reconcileRework(
                                "P1", 700L, new BigDecimal("0.5"), new BigDecimal("0.5"), false));
        unchanged();
    }

    @Test
    void failureToPersistTheReinspectionResultRollsBackTheWholeQualityTransaction() {
        rework();
        var mapper =
                mock(
                        MesTaskMapper.class,
                        org.mockito.AdditionalAnswers.delegatesTo(
                                sessions.getMapper(MesTaskMapper.class)));
        doReturn(0)
                .when(mapper)
                .update(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        doReturn(0)
                .when(mapper)
                .update(
                        nullable(MesTaskEntity.class),
                        any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        ReflectionTestUtils.setField(tasks, "baseMapper", mapper);
        assertThrows(CheckedException.class, () -> tasks.syncReworkInspection(reworkResult()));
        unchanged();
        quantity("mes_task", "rework_qualified_num", 0);
    }

    @Test
    void routeReturnExecutionEvidenceDoesNotIncreaseTheReinspectionOutputQuantity() {
        rework();
        db.update(
                "INSERT INTO"
                    + " mes_task_report(id,tenant_id,factory_code,task_code,produce_task_code,arrange_code,report_num,quality_released_num,quality_pending_num,quality_rejected_num,quality_quantity_status,status,execution_kind)"
                    + " VALUES(102,1,'F1','T1','P1','A1',1,0,0,0,'NOT_REQUIRED','EFFECTIVE','ROUTE_RETURN')");
        var result =
                quality.reconcileRework("P1", 700L, new BigDecimal("5"), new BigDecimal("5"), true);
        assertEquals(0, new BigDecimal("5").compareTo(result.getReportedQuantity()));
        assertEquals(
                0,
                BigDecimal.ZERO.compareTo(
                        db.queryForObject(
                                "SELECT quality_released_num FROM mes_task_report WHERE id=102",
                                BigDecimal.class)));
    }

    private void reworkQuantities(int released, int rejected, int pending, int proofs) {
        quantity("mes_task_report", "quality_released_num", released);
        quantity("mes_task_report", "quality_rejected_num", rejected);
        quantity("mes_task_report", "quality_pending_num", pending);
        quantity("mes_task", "rework_qualified_num", released);
        quantity("mes_task", "rework_rejected_num", rejected);
        quantity("mes_task", "un_in_stock_num", released);
        assertEquals(proofs, count("mes_quality_stock_proof"));
    }

    @Test
    void aNodeExecutionContractCanReleaseItsOwnPendingOutput() {
        nodeContract();
        quality.reconcile("P1", passed());
        quantity("mes_task", "un_in_stock_num", 5);
    }

    @Test
    void aDispatchFromADifferentFrozenRouteCannotConsumeThisQualityResult() {
        db.update("UPDATE mes_task_arrange SET route_code='R9'");
        assertThrows(CheckedException.class, () -> quality.reconcile("P1", passed()));
        unchanged();
    }

    @Test
    void aDifferentStableNodeCannotConsumeThisQualityResultEvenAtTheSameMainProcess() {
        nodeContract();
        db.update("UPDATE mes_task_arrange SET source_route_node_code='RN-9'");
        assertThrows(CheckedException.class, () -> quality.reconcile("P1", passed()));
        unchanged();
    }

    @Test
    void severalReportsOfTheSameDispatchAccumulateOnceAcrossTheWholeSyncTransaction() {
        db.update("UPDATE mes_task_report SET report_num=2,quality_pending_num=2 WHERE id=100");
        db.update("UPDATE mes_warehouse_live_stock SET num=2 WHERE id=201");
        db.update(
                "INSERT INTO"
                    + " mes_task_report(id,tenant_id,factory_code,task_code,produce_task_code,arrange_code,report_num,quality_released_num,quality_pending_num,quality_rejected_num,quality_quantity_status,status,report_time)"
                    + " VALUES(102,1,'F1','T1','P1','A1',3,0,3,0,'PENDING','EFFECTIVE',CURRENT_TIMESTAMP)");
        db.update(
                "INSERT INTO mes_task_report_record(id,tenant_id,factory_code,report_id,type)"
                        + " VALUES(103,1,'F1',102,'REPORT')");
        db.update(
                "INSERT INTO"
                    + " mes_warehouse_live_stock(id,tenant_id,factory_code,source_report_id,mat_code,mat_unit,num,inspection_status,status)"
                    + " VALUES(202,1,'F1',102,'MAT1','PCS',3,'PENDING','1')");
        quality.reconcile("P1", passed());
        quality.reconcile("P1", passed());
        quantity("mes_produce_task", "quality_released_num", 5);
        quantity("mes_task_arrange", "quality_released_num", 5);
        quantity("mes_task", "un_in_stock_num", 5);
        assertEquals(2, count("mes_quality_stock_proof"));
        assertEquals(
                0,
                new BigDecimal("5")
                        .compareTo(
                                db.queryForObject(
                                        "SELECT sum(quality_released_num) FROM mes_task_report",
                                        BigDecimal.class)));
    }

    @Test
    void aRealProcessWithoutReportsReturnsZeroQuantitiesWithoutMakingStockOrAudit() {
        db.update("DELETE FROM mes_task_report_record");
        db.update("DELETE FROM mes_warehouse_live_stock");
        db.update("DELETE FROM mes_task_report");
        var result = quality.reconcile("P1", passed());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.getReportedQuantity()));
        assertEquals(0, count("mes_quality_stock_proof"));
        assertEquals(0, count("mes_task_report_record"));
        quantity("mes_task", "un_in_stock_num", 0);
    }

    private void nodeContract() {
        for (String table : List.of("mes_produce_task", "mes_task_arrange"))
            db.update(
                    "UPDATE "
                            + table
                            + " SET route_execution_schema_version='NODE_EXECUTION_V1',"
                            + "source_route_node_code='RN-1',predecessor_node_codes='',successor_node_codes=''");
    }

    private void compete(String mapper, String table, boolean holdRoot, Runnable sync)
            throws Exception {
        competing = true;
        observedMapper = mapper;
        var pool = Executors.newSingleThreadExecutor(r -> new Thread(r, "quality-sync"));
        var future = new AtomicReference<Future<?>>();
        var failure = new AtomicReference<Throwable>();
        try {
            new TransactionTemplate(transactions)
                    .executeWithoutResult(
                            status -> {
                                db.queryForObject(
                                        "SELECT task_code FROM business_lock WHERE task_code='T1'"
                                                + " FOR UPDATE",
                                        String.class);
                                if (holdRoot)
                                    db.queryForObject(
                                            "SELECT id FROM mes_task WHERE id=1 FOR UPDATE",
                                            Long.class);
                                future.set(
                                        pool.submit(
                                                () -> {
                                                    context();
                                                    try {
                                                        sync.run();
                                                    } finally {
                                                        clearContext();
                                                    }
                                                }));
                                await(boundary);
                                try {
                                    db.queryForObject(
                                            "SELECT id FROM " + table + " WHERE id=? FOR UPDATE",
                                            Long.class,
                                            table.equals("mes_task") ? 1L : 100L);
                                } catch (Throwable caught) {
                                    failure.set(caught);
                                } finally {
                                    continueQuality.countDown();
                                }
                            });
            future.get().get(10, TimeUnit.SECONDS);
            assertNull(failure.get(), "生产已持有工单锁，独立质量同步不能先占报工/根行形成反向等待: " + failure.get());
        } finally {
            continueQuality.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS))
                throw new AssertionError("transaction boundary timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private MesQualityGateDecision passed() {
        return new MesQualityGateDecision()
                .setAllowed(true)
                .setPassed(true)
                .setGateMode("NONE")
                .setPhase("REPORT")
                .setMessage("controlled QMS decision");
    }

    private void unchanged() {
        assertEquals(0, count("mes_quality_stock_proof"));
        assertEquals(1, count("mes_warehouse_live_stock"));
        assertEquals(
                "PENDING",
                db.queryForObject(
                        "SELECT inspection_status FROM mes_warehouse_live_stock", String.class));
        quantity("mes_task_report", "quality_pending_num", 5);
        quantity("mes_task_report", "quality_released_num", 0);
        quantity("mes_task_arrange", "quality_released_num", 0);
        assertEquals(
                0,
                new BigDecimal("0")
                        .compareTo(
                                db.queryForObject(
                                        "SELECT un_in_stock_num FROM mes_task WHERE id=1",
                                        BigDecimal.class)));
        assertEquals(1, count("mes_task_report_record"));
    }

    private int count(String table) {
        return db.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void quantity(String table, String column, int value) {
        long id =
                switch (table) {
                    case "mes_task_report" -> 100L;
                    case "mes_task_arrange" -> 11L;
                    case "mes_produce_task" -> 21L;
                    case "mes_task" -> 1L;
                    default -> throw new AssertionError("unknown fixture table");
                };
        assertEquals(
                0,
                BigDecimal.valueOf(value)
                        .compareTo(
                                db.queryForObject(
                                        "SELECT " + column + " FROM " + table + " WHERE id=?",
                                        BigDecimal.class,
                                        id)));
    }

    private void context() {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private void clearContext() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clear() {
        continueQuality.countDown();
        clearContext();
    }

    // 复用真实数量/库存夹具验证公开质量门的消费结果，避免另造一个数量算法。
    void assertBlockedDecisionKeepsHistoricalOutputPending(MesQualityGateDecision decision) {
        quality.reconcile("P1", decision);
        quantity("mes_task_report", "quality_released_num", 0);
        quantity("mes_task_report", "quality_pending_num", 5);
        quantity("mes_task_arrange", "quality_released_num", 0);
        quantity("mes_produce_task", "quality_released_num", 0);
        assertEquals(
                "PENDING",
                db.queryForObject(
                        "SELECT inspection_status FROM mes_warehouse_live_stock", String.class));
    }

    private <T> T dependency(Class<T> type) {
        return type.cast(dependencies.computeIfAbsent(type, k -> mock(type)));
    }

    @SuppressWarnings("unchecked")
    private <T> T construct(Class<T> type) throws Exception {
        var constructor =
                Arrays.stream(type.getDeclaredConstructors())
                        .max(Comparator.comparingInt(Constructor::getParameterCount))
                        .orElseThrow();
        return (T)
                constructor.newInstance(
                        Arrays.stream(constructor.getParameterTypes())
                                .map(this::dependency)
                                .toArray());
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(
                        transactions, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void store(Class service, Class entity, Class mapper) {
        var actual = (BaseMapper) sessions.getMapper(mapper);
        var delegate =
                new ServiceImpl() {
                    @Override
                    public BaseMapper getBaseMapper() {
                        return actual;
                    }

                    @Override
                    public Class getEntityClass() {
                        return entity;
                    }
                };
        dependencies.put(
                service,
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class[] {service},
                        (p, m, args) -> {
                            try {
                                return delegate.getClass()
                                        .getMethod(m.getName(), m.getParameterTypes())
                                        .invoke(delegate, args);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        }));
    }

    private void table(MybatisConfiguration config, Class<?> type) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(config, "quality-ddl"), type);
        var info = TableInfoHelper.getTableInfo(type);
        var ddl =
                new StringBuilder("CREATE TABLE ")
                        .append(info.getTableName())
                        .append("(id bigint primary key");
        for (var field : info.getFieldList()) {
            var t = field.getPropertyType();
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
            ddl.append(',').append(field.getColumn()).append(' ').append(sql);
            if (field.getColumn().equals("del_flag")) ddl.append(" default '0'");
        }
        db.execute(ddl.append(')').toString());
    }

    @Intercepts(
            @Signature(
                    type = Executor.class,
                    method = "query",
                    args = {
                        MappedStatement.class,
                        Object.class,
                        RowBounds.class,
                        ResultHandler.class
                    }))
    public static final class QueryObserver implements Interceptor {
        Consumer<String> afterQuery = key -> {};

        @Override
        public Object intercept(Invocation call) throws Throwable {
            Object value = call.proceed();
            var statement = (MappedStatement) call.getArgs()[0];
            if (statement
                    .getBoundSql(call.getArgs()[1])
                    .getSql()
                    .toUpperCase(Locale.ROOT)
                    .contains("FOR UPDATE")) {
                String key = statement.getId();
                afterQuery.accept(
                        key.substring(
                                key.lastIndexOf('.', key.lastIndexOf('.') - 1) + 1,
                                key.lastIndexOf('.')));
            }
            return value;
        }
    }
}
