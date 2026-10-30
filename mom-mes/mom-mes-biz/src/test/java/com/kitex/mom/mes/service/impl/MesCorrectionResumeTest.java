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
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mes.dto.MesTaskReportReverseVO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.*;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
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

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 实际冲销/复工Service与MyBatis事务；库存及身份/开工门禁端口受控，不宣称运行联验。 */
class MesCorrectionResumeTest {
    private JdbcTemplate db;
    private SqlSessionTemplate sessions;
    private DataSourceTransactionManager transactions;
    private MesTaskReportReverseService reverse;
    private MesTaskArrangeService arrangements;
    private boolean failHistorySave;
    private final Map<Class<?>, Object> dependencies = new HashMap<>();

    @BeforeEach
    void setup() throws Exception {
        dependencies.clear();
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        login("worker");
        var ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:correction_"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(ds);
        transactions = new DataSourceTransactionManager(ds);
        db.execute("CREATE SCHEMA mom_mes");
        db.execute("CREATE TABLE stock_effect(request_id varchar,quantity decimal(24,8))");
        db.execute("CREATE TABLE execution_lock(id integer primary key)");
        db.update("INSERT INTO execution_lock VALUES(1)");
        var config = new MybatisConfiguration();
        var global = new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(
                new com.kitex.common.data.mybatis.MybatisPlusMetaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(config, global);
        var types =
                List.of(
                        MesTaskReportEntity.class,
                        MesTaskReportRecordEntity.class,
                        MesTaskArrangeEntity.class,
                        MesProduceTaskEntity.class,
                        MesTaskEntity.class,
                        MesWarehouseInoutEntity.class,
                        MesReportMaterialConsumeEntity.class,
                        MesWarehouseFeedEntity.class,
                        MesReportQuantityCorrectionLineEntity.class,
                        MesWipTransferEntity.class,
                        MesToolingExecutionEntity.class,
                        MesTaskExecutionSegmentEntity.class,
                        MesWarehouseLiveStockEntity.class);
        for (var type : types) table(config, type);
        for (var mapper :
                List.of(
                        MesTaskReportMapper.class,
                        MesTaskReportRecordMapper.class,
                        MesTaskArrangeMapper.class,
                        MesProduceTaskMapper.class,
                        MesTaskMapper.class,
                        MesWarehouseInoutMapper.class,
                        MesReportMaterialConsumeMapper.class,
                        MesWarehouseFeedMapper.class,
                        MesReportQuantityCorrectionLineMapper.class,
                        MesWipTransferMapper.class,
                        MesToolingExecutionMapper.class,
                        MesTaskExecutionSegmentMapper.class,
                        MesWarehouseLiveStockMapper.class)) config.addMapper(mapper);
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
        factory.setPlugins(interceptors);
        sessions = new SqlSessionTemplate(factory.getObject());
        store(MesTaskReportService.class, MesTaskReportEntity.class, MesTaskReportMapper.class);
        store(
                MesTaskReportRecordService.class,
                MesTaskReportRecordEntity.class,
                MesTaskReportRecordMapper.class);
        store(MesProduceTaskService.class, MesProduceTaskEntity.class, MesProduceTaskMapper.class);
        store(MesTaskService.class, MesTaskEntity.class, MesTaskMapper.class);
        store(
                MesWarehouseInoutService.class,
                MesWarehouseInoutEntity.class,
                MesWarehouseInoutMapper.class);
        store(
                MesReportMaterialConsumeService.class,
                MesReportMaterialConsumeEntity.class,
                MesReportMaterialConsumeMapper.class);
        store(
                MesWarehouseFeedService.class,
                MesWarehouseFeedEntity.class,
                MesWarehouseFeedMapper.class);
        store(
                MesReportQuantityCorrectionLineService.class,
                MesReportQuantityCorrectionLineEntity.class,
                MesReportQuantityCorrectionLineMapper.class);
        dependencies.put(
                MesWipTransferMapper.class, sessions.getMapper(MesWipTransferMapper.class));
        dependencies.put(
                MesToolingExecutionMapper.class,
                sessions.getMapper(MesToolingExecutionMapper.class));
        dependencies.put(
                MesTaskExecutionSegmentMapper.class,
                sessions.getMapper(MesTaskExecutionSegmentMapper.class));
        var access = dependency(MesActualFactoryAccessService.class);
        when(access.require("write")).thenReturn("F1");
        var groups = dependency(MesIdentifierBindingGroupService.class);
        doAnswer(
                        call -> {
                            db.queryForObject(
                                    "SELECT id FROM execution_lock WHERE id=1 FOR UPDATE",
                                    Integer.class);
                            return null;
                        })
                .when(groups)
                .lockDispatchTask(anyString());
        dependencies.put(
                MesExecutionTaskLock.class,
                new MesExecutionTaskLock(
                        access,
                        groups,
                        sessions.getMapper(MesTaskArrangeMapper.class),
                        sessions.getMapper(MesTaskReportMapper.class)));
        // 库存记账端口以同事务审计记录代替真实库存；不变更WMS或宣称库存算法受测。
        doAnswer(
                        call -> {
                            var dto =
                                    (com.kitex.mom.mes.dto.MesWarehouseLiveStockInoutDTO)
                                            call.getArgument(0);
                            db.update(
                                    "INSERT INTO stock_effect VALUES(?,?)",
                                    dto.getResourceNo(),
                                    dto.getNum());
                            return null;
                        })
                .when(dependency(MesWarehouseLiveStockService.class))
                .outStock(any());
        when(dependency(MesInboundAllocationService.class)
                        .activeByTask(anyString(), anyString(), anyBoolean()))
                .thenReturn(List.of());
        when(dependency(MesDeviceGateService.class).check(any(), any(), any(), any()))
                .thenAnswer(
                        call -> new com.kitex.mom.mes.dto.MesDeviceGateDecision().setAllowed(true));
        var target = construct(MesTaskArrangeServiceImpl.class);
        ReflectionTestUtils.setField(
                target, "baseMapper", sessions.getMapper(MesTaskArrangeMapper.class));
        arrangements = proxied(target);
        dependencies.put(MesTaskArrangeService.class, arrangements);
        reverse = proxied(construct(MesTaskReportReverseServiceImpl.class));
        db.update(
                "INSERT INTO"
                    + " mes_task(id,tenant_id,factory_code,code,status,notice_num,complete_num,un_in_stock_num,in_stock_num,completion_version,mat_code,plan_no)"
                    + " VALUES(1,1,'F1','T1','FINISH',10,10,10,0,0,'MAT1','PLAN-1')");
        db.update(
                "INSERT INTO"
                    + " mes_produce_task(id,tenant_id,factory_code,code,task_code,route_code,last_route,status,notice_num,complete_num,quality_released_num,finish_time)"
                    + " VALUES(21,1,'F1','P1','T1','R1',true,'FINISH',10,10,10,TIMESTAMP"
                    + " '2026-10-24 11:00:00')");
        db.update("UPDATE mes_produce_task SET process_route_code='ROUTE-1'");
        db.update(
                "INSERT INTO"
                    + " mes_task_arrange(id,tenant_id,factory_code,code,task_code,produce_task_code,route_code,last_route,status,notice_num,complete_num,quality_released_num,start_time,finish_time,finish_hour,worker_code,report_mode,route_type,actual_worksite_code,worksite_codes,workshop_code,shift_code)"
                    + " VALUES(11,1,'F1','A1','T1','P1','R1',true,'FINISH',10,10,10,TIMESTAMP"
                    + " '2026-10-24 10:00:00',TIMESTAMP '2026-10-24"
                    + " 11:00:00',1,'worker','QUANTITY','QUANTITY','WS1','WS1','W1','SHIFT1')");
        db.update(
                "INSERT INTO"
                    + " mes_task_report(id,tenant_id,factory_code,request_id,task_code,produce_task_code,arrange_code,report_mode,report_num,effective_report_num,corrected_report_num,status,quality_quantity_status,report_unit)"
                    + " VALUES(41,1,'F1','REPORT_41','T1','P1','A1','QUANTITY',10,10,0,'EFFECTIVE','NOT_REQUIRED','PCS')");
        db.update(
                "INSERT INTO"
                    + " mes_task_report_record(id,tenant_id,factory_code,report_id,type,status,report_num,effective_report_num,corrected_report_num)"
                    + " VALUES(51,1,'F1',41,'REPORT','EFFECTIVE',10,10,0)");
        db.update(
                "INSERT INTO"
                    + " mes_warehouse_inout(id,tenant_id,factory_code,bill_id,resource_no,biz_type,operate_type,operate_num)"
                    + " VALUES(61,1,'F1',41,'REPORT_41',?,?,10)",
                WarehouseInBizTypeEnum.PROCESS_PRODUCT_IN.getCode(),
                InOutEnum.IN.getCode());
        db.update(
                "INSERT INTO"
                    + " mes_task_execution_segment(id,tenant_id,factory_code,arrange_id,arrange_code,task_code,produce_task_code,segment_no,segment_type,status,start_time,end_time,duration_seconds,close_reason,sample_eligible)"
                    + " VALUES(71,1,'F1',11,'A1','T1','P1',1,'RUN','CLOSED',TIMESTAMP '2026-10-24"
                    + " 10:00:00',TIMESTAMP '2026-10-24 11:00:00',3600,'FINISH',true)");
    }

    private void login(String name) {
        var user = mock(MomUser.class);
        when(user.getUsername()).thenReturn(name);
        when(user.getName()).thenReturn(name);
        when(user.getId()).thenReturn(2L);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, "N/A", List.of()));
    }

    private MesTaskReportReverseVO command(String id, BigDecimal quantity) {
        var c = new MesTaskReportReverseVO();
        c.setReportId(41L);
        c.setRequestId(id);
        c.setReason("测试录入纠正");
        c.setCorrectionQuantity(quantity);
        return c;
    }

    private void partial(String id) {
        reverse.reversePartial(command(id, new BigDecimal("2")), 90L, "a".repeat(64));
    }

    private String state() {
        return db.queryForObject("SELECT status FROM mes_task_arrange WHERE id=11", String.class);
    }

    @Test
    void aValidPausedDispatchWithLiveParentsCanAlreadyResume() {
        db.update("UPDATE mes_task_arrange SET status='PAUSE' WHERE id=11");
        db.update("UPDATE mes_produce_task SET status='PAUSE' WHERE id=21");
        db.update("UPDATE mes_task SET status='PRODUCT' WHERE id=1");
        assertDoesNotThrow(() -> arrangements.start(11L));
        assertEquals("PRODUCT", state());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM mes_task_execution_segment WHERE status='OPEN'",
                        Integer.class));
    }

    @Test
    void correctingFinishedDispatchWaitsForActualWorkerResumeWithoutInventingRunTime() {
        partial("correction_00000001");
        assertEquals("PAUSE", state());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM mes_task_execution_segment WHERE status='OPEN'",
                        Integer.class));
        assertEquals(
                "PRODUCT",
                db.queryForObject("SELECT status FROM mes_task WHERE id=1", String.class));
        assertNull(
                db.queryForObject(
                        "SELECT finish_time FROM mes_task_arrange WHERE id=11",
                        LocalDateTime.class));
        assertEquals(
                3600L,
                db.queryForObject(
                        "SELECT duration_seconds FROM mes_task_execution_segment WHERE id=71",
                        Long.class));
    }

    @Test
    void twoPartialCorrectionsAppendIndependentHistoryAndKeepExactCumulativeQuantities() {
        partial("correction_00000001");
        assertDoesNotThrow(() -> partial("correction_00000002"));
        assertEquals(
                3,
                db.queryForObject(
                        "SELECT count(*) FROM mes_task_report_record WHERE report_id=41",
                        Integer.class));
        assertEquals(
                0,
                new BigDecimal("6")
                        .compareTo(
                                db.queryForObject(
                                        "SELECT effective_report_num FROM mes_task_report WHERE"
                                            + " id=41",
                                        BigDecimal.class)));
        assertEquals(
                3,
                db.queryForObject(
                        "SELECT count(DISTINCT id) FROM mes_task_report_record WHERE report_id=41",
                        Integer.class));
    }

    @Test
    void aLaterFullReverseUsesTheSameWaitingStateAndDoesNotReplacePartialHistory() {
        partial("correction_00000001");
        reverse.reverse(command("reverse_00000000002", null));
        assertEquals("PAUSE", state());
        assertEquals(
                3,
                db.queryForObject(
                        "SELECT count(*) FROM mes_task_report_record WHERE report_id=41",
                        Integer.class));
        assertEquals(
                0,
                BigDecimal.ZERO.compareTo(
                        db.queryForObject(
                                "SELECT complete_num FROM mes_task_arrange WHERE id=11",
                                BigDecimal.class)));
    }

    @Test
    void aWorkerCanResumeThroughTheActualStartCommandAndOnlyTheNewRunBeginsNow() {
        partial("correction_00000001");
        var earliest = LocalDateTime.now().minusSeconds(1);
        assertDoesNotThrow(() -> arrangements.start(11L));
        assertEquals("PRODUCT", state());
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT count(*) FROM mes_task_execution_segment", Integer.class));
        var resumed =
                db.queryForObject(
                        "SELECT start_time FROM mes_task_execution_segment WHERE status='OPEN'",
                        LocalDateTime.class);
        assertFalse(resumed.isBefore(earliest));
        assertEquals(
                LocalDateTime.of(2026, 10, 24, 11, 0),
                db.queryForObject(
                        "SELECT end_time FROM mes_task_execution_segment WHERE id=71",
                        LocalDateTime.class));
    }

    @Test
    void aFinishedDispatchCannotBeFinishedAgainUntilAnExplicitResume() {
        partial("correction_00000001");
        assertThrows(RuntimeException.class, () -> arrangements.finish(11L));
        assertEquals("PAUSE", state());
    }

    @Test
    void aDirectFullReverseAlsoWaitsForResumeAndKeepsItsOriginalReplay() {
        reverse.reverse(command("reverse_00000000001", null));
        assertEquals("PAUSE", state());
        reverse.reverse(command("reverse_00000000001", null));
        assertEquals(
                2,
                db.queryForObject(
                        "SELECT count(*) FROM mes_task_report_record WHERE report_id=41",
                        Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRODUCT", "PAUSE", "CLOSE"})
    void aCorrectionDoesNotInventAStateTransitionForAnUnfinishedOrClosedDispatch(String status) {
        db.update("UPDATE mes_task_arrange SET status=? WHERE id=11", status);
        if (status.equals("PRODUCT"))
            db.update(
                    "UPDATE mes_task_execution_segment SET"
                        + " status='OPEN',end_time=NULL,duration_seconds=NULL WHERE id=71");
        partial("correction_00000001");
        assertEquals(status, state());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM mes_task_execution_segment", Integer.class));
    }

    @Test
    void aMissingAppendReceiptRollsBackAllCorrectionQuantitiesAndControlledInventoryEffects() {
        failHistorySave = true;
        assertThrows(
                com.kitex.common.core.exception.CheckedException.class,
                () -> partial("correction_00000001"));
        assertEquals("FINISH", state());
        assertEquals(
                0,
                new BigDecimal("10")
                        .compareTo(
                                db.queryForObject(
                                        "SELECT complete_num FROM mes_task_arrange WHERE id=11",
                                        BigDecimal.class)));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM stock_effect", Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM mes_report_quantity_correction_line", Integer.class));
    }

    @Test
    void aDatabaseHistoryFailureRollsBackTheAlreadyWrittenCorrection() {
        db.execute(
                "ALTER TABLE mes_task_report_record ADD CONSTRAINT block_correction"
                    + " CHECK(type<>'QUANTITY_CORRECTION')");
        assertThrows(RuntimeException.class, () -> partial("correction_00000001"));
        assertEquals("FINISH", state());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM stock_effect", Integer.class));
        assertEquals(
                0,
                new BigDecimal("10")
                        .compareTo(
                                db.queryForObject(
                                        "SELECT effective_report_num FROM mes_task_report WHERE"
                                            + " id=41",
                                        BigDecimal.class)));
    }

    @Test
    void aDifferentWorkerCannotResumeTheCorrectedDispatch() {
        partial("correction_00000001");
        login("other-worker");
        assertThrows(
                com.kitex.common.core.exception.CheckedException.class,
                () -> arrangements.start(11L));
        assertEquals("PAUSE", state());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM mes_task_execution_segment WHERE status='OPEN'",
                        Integer.class));
    }

    @Test
    void aDeviceGateRefusalLeavesTheCorrectedDispatchWaitingWithoutANewRun() {
        partial("correction_00000001");
        when(dependency(MesDeviceGateService.class).check(any(), any(), any(), any()))
                .thenReturn(
                        new com.kitex.mom.mes.dto.MesDeviceGateDecision()
                                .setAllowed(false)
                                .setMessage("controlled unavailable"));
        assertThrows(
                com.kitex.common.core.exception.CheckedException.class,
                () -> arrangements.start(11L));
        assertEquals("PAUSE", state());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM mes_task_execution_segment", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"FINISH", "CLOSE"})
    void resumingMustNotReactivateACompletedOrClosedParentProcess(String status) {
        db.update("UPDATE mes_task_arrange SET status='PAUSE' WHERE id=11");
        db.update("UPDATE mes_produce_task SET status=? WHERE id=21", status);
        assertThrows(
                com.kitex.common.core.exception.CheckedException.class,
                () -> arrangements.start(11L));
        assertEquals("PAUSE", state());
        assertEquals(
                status,
                db.queryForObject("SELECT status FROM mes_produce_task WHERE id=21", String.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"FINISH", "CLOSE"})
    void resumingMustNotStartAChildOfACompletedOrClosedMainTask(String status) {
        db.update("UPDATE mes_task_arrange SET status='PAUSE' WHERE id=11");
        db.update("UPDATE mes_produce_task SET status='PAUSE' WHERE id=21");
        db.update("UPDATE mes_task SET status=? WHERE id=1", status);
        assertThrows(
                com.kitex.common.core.exception.CheckedException.class,
                () -> arrangements.start(11L));
        assertEquals("PAUSE", state());
        assertEquals(
                status, db.queryForObject("SELECT status FROM mes_task WHERE id=1", String.class));
    }

    private <T> T dependency(Class<T> type) {
        return type.cast(dependencies.computeIfAbsent(type, k -> mock(type)));
    }

    private <T> T construct(Class<T> type) throws Exception {
        var c = type.getDeclaredConstructors()[0];
        return type.cast(
                c.newInstance(
                        Arrays.stream(c.getParameterTypes()).map(this::dependency).toArray()));
    }

    private <T> T proxied(T target) {
        var p = new ProxyFactory(target);
        p.setProxyTargetClass(true);
        p.addAdvice(
                new TransactionInterceptor(
                        transactions, new AnnotationTransactionAttributeSource()));
        return (T) p.getProxy();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void store(Class<T> service, Class entity, Class mapper) {
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
        Object proxy =
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class[] {service},
                        (p, m, args) -> {
                            if (failHistorySave
                                    && service == MesTaskReportRecordService.class
                                    && m.getName().equals("save")) return false;
                            try {
                                return delegate.getClass()
                                        .getMethod(m.getName(), m.getParameterTypes())
                                        .invoke(delegate, args);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
        dependencies.put(service, proxy);
    }

    private void table(MybatisConfiguration config, Class<?> type) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(config, "correction-ddl"), type);
        var info = TableInfoHelper.getTableInfo(type);
        var ddl =
                new StringBuilder("CREATE TABLE ")
                        .append(info.getTableName())
                        .append("(id bigint primary key");
        for (var field : info.getFieldList()) {
            Class<?> t = field.getPropertyType();
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
        db.execute(
                "CREATE VIEW mom_mes."
                        + info.getTableName()
                        + " AS SELECT * FROM "
                        + info.getTableName());
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        SecurityContextHolder.clearContext();
    }
}
