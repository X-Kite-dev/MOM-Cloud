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
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.process.*;
import com.kitex.mom.mes.process.impl.*;
import com.kitex.mom.mes.service.*;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
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

/** 公开GOOD/NG与实际CRUD/事务；命令账、外部质量/库存/标识门为受控端口。 */
class MesReportParentStateTest {
    private JdbcTemplate db;
    private SqlSessionTemplate sessions;
    private MesTaskReportService reports;
    private final Map<Class<?>, Object> dependencies = new HashMap<>();
    private boolean approvedReturn;
    private Long replayId;

    @BeforeEach
    void setup() throws Exception {
        dependencies.clear();
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var user = mock(MomUser.class);
        when(user.getId()).thenReturn(2L);
        when(user.getTenantId()).thenReturn(1L);
        when(user.getUsername()).thenReturn("worker");
        when(user.getName()).thenReturn("worker");
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, "N/A", List.of()));
        var ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:parent_state_"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(ds);
        db.execute(
                "CREATE TABLE inventory_effect(report_id bigint,kind varchar,quantity"
                        + " decimal(24,8))");
        db.execute("CREATE TABLE command_receipt(report_id bigint)");
        db.execute("CREATE TABLE pass_receipt(report_id bigint)");
        db.execute("CREATE TABLE task_lock(id integer primary key)");
        db.update("INSERT INTO task_lock VALUES(1)");
        var config = new MybatisConfiguration();
        var global = new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(
                new com.kitex.common.data.mybatis.MybatisPlusMetaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(config, global);
        for (var type :
                List.of(
                        MesTaskReportEntity.class,
                        MesTaskReportRecordEntity.class,
                        MesTaskArrangeEntity.class,
                        MesProduceTaskEntity.class,
                        MesTaskEntity.class,
                        MesTaskReportUnqualifiedEntity.class,
                        MesReportReverseRequestEntity.class)) table(config, type);
        for (var type :
                List.of(
                        MesTaskReportMapper.class,
                        MesTaskReportRecordMapper.class,
                        MesTaskArrangeMapper.class,
                        MesProduceTaskMapper.class,
                        MesTaskMapper.class,
                        MesTaskReportUnqualifiedMapper.class,
                        MesReportReverseRequestMapper.class)) config.addMapper(type);
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
        store(MesTaskArrangeService.class, MesTaskArrangeEntity.class, MesTaskArrangeMapper.class);
        store(MesTaskService.class, MesTaskEntity.class, MesTaskMapper.class);
        store(MesProduceTaskService.class, MesProduceTaskEntity.class, MesProduceTaskMapper.class);
        store(
                MesTaskReportRecordService.class,
                MesTaskReportRecordEntity.class,
                MesTaskReportRecordMapper.class);
        store(
                MesTaskReportUnqualifiedService.class,
                MesTaskReportUnqualifiedEntity.class,
                MesTaskReportUnqualifiedMapper.class);
        var access = dependency(MesActualFactoryAccessService.class);
        when(access.require("write")).thenReturn("F1");
        var groups = dependency(MesIdentifierBindingGroupService.class);
        doAnswer(
                        call -> {
                            db.queryForObject(
                                    "SELECT id FROM task_lock WHERE id=1 FOR UPDATE",
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
        dependencies.put(
                MesReportReverseRequestMapper.class,
                sessions.getMapper(MesReportReverseRequestMapper.class));
        when(dependency(MesReportCommandService.class).capture(any(), any()))
                .thenAnswer(
                        call -> {
                            var p = (MesTaskReportVO) call.getArgument(0);
                            return new MesReportCommandService.CapturedCommand(
                                    p.getRequestId(),
                                    "F1",
                                    1L,
                                    2L,
                                    "worker",
                                    "STATION",
                                    "TEST",
                                    null,
                                    "a".repeat(64),
                                    replayId);
                        });
        doAnswer(
                        call -> {
                            db.update(
                                    "INSERT INTO command_receipt VALUES(?)",
                                    ((MesTaskReportEntity) call.getArgument(1)).getId());
                            return null;
                        })
                .when(dependency(MesReportCommandService.class))
                .accept(any(), any());
        doAnswer(
                        call -> {
                            var p = (MesTaskReportVO) call.getArgument(0);
                            p.setExecutionKind(approvedReturn ? "ROUTE_RETURN" : "NORMAL");
                            p.setIdentifierRouteVersion(approvedReturn ? 2 : 1);
                            p.setRouteReturnId(approvedReturn ? 90L : null);
                            return null;
                        })
                .when(dependency(MesIdentifierRouteReturnCoordinator.class))
                .prepareExecution(any(), any(), any(), any());
        when(dependency(MesDeviceGateService.class).check(any(), any(), any(), any()))
                .thenReturn(
                        new MesDeviceGateDecision()
                                .setAllowed(true)
                                .setDeviceCode("D1")
                                .setWorksiteCode("WS1"));
        when(dependency(MesProcessTimingGuard.class)
                        .validate(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new MesProcessTimingGuard.Decision(
                                LocalDateTime.now(), "{}", "[]", null, List.of()));
        when(dependency(MesQualityGateService.class).checkReport(anyString()))
                .thenReturn(
                        new MesQualityGateDecision()
                                .setAllowed(true)
                                .setPassed(true)
                                .setGateMode("NONE")
                                .setMessage("controlled exempt")
                                .setPhase("REPORT"));
        when(dependency(MesQualityQuantityService.class).reconcile(anyString(), any()))
                .thenAnswer(call -> call.getArgument(1));
        var mat = new MdmMaterialEntity();
        mat.setMaterialCode("MAT1");
        mat.setStatus(true);
        mat.setBasicUnit("PCS");
        mat.setFactoryCode("F1");
        mat.setTenantId(1L);
        when(dependency(RemoteMaterialService.class).getDetails("MAT1")).thenReturn(R.ok(mat));
        var quantity =
                new QuantityProcessOperation(
                        mock(MesWarehouseLiveStockService.class), null, null, null) {
                    @Override
                    public void use(MesTaskReportVO p) {
                        effect(p, "USE");
                    }

                    @Override
                    public void in(MesTaskReportVO p) {
                        effect(p, "OUTPUT");
                    }
                };
        var one =
                new OneCodeProcessOperation(
                        mock(MesWarehouseLiveStockService.class), null, null, null) {
                    @Override
                    public void use(MesTaskReportVO p) {
                        effect(p, "USE");
                    }

                    @Override
                    public void in(MesTaskReportVO p) {
                        effect(p, "OUTPUT");
                    }
                };
        dependencies.put(
                ProcessOperationFactory.class, new ProcessOperationFactory(List.of(quantity, one)));
        doAnswer(
                        call -> {
                            db.update(
                                    "INSERT INTO pass_receipt VALUES(?)",
                                    ((MesTaskReportEntity) call.getArgument(4)).getId());
                            return null;
                        })
                .when(dependency(MesStationPassService.class))
                .recordAccepted(any(), any(), any(), any(), any());
        when(dependency(MesProductionMaterialOperationService.class).mode(any()))
                .thenAnswer(call -> call.getArgument(0) == null ? "NONE" : call.getArgument(0));
        when(dependency(MesExecutionTemplateService.class)
                        .contract(any(MesTaskArrangeEntity.class)))
                .thenReturn(
                        new MesExecutionContractDTO()
                                .setIdentifierNodePolicy(new MesIdentifierNodePolicyDTO()));
        var c = MesTaskReportServiceImpl.class.getDeclaredConstructors()[0];
        var target =
                (MesTaskReportServiceImpl)
                        c.newInstance(
                                Arrays.stream(c.getParameterTypes())
                                        .map(this::dependency)
                                        .toArray());
        ReflectionTestUtils.setField(
                target, "baseMapper", sessions.getMapper(MesTaskReportMapper.class));
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(
                        new DataSourceTransactionManager(ds),
                        new AnnotationTransactionAttributeSource()));
        reports = (MesTaskReportService) proxy.getProxy();
        db.update(
                "INSERT INTO"
                    + " mes_task(id,tenant_id,factory_code,code,status,notice_num,complete_num,un_in_stock_num,un_pass_num,mat_code)"
                    + " VALUES(1,1,'F1','T1','PRODUCT',10,0,0,0,'MAT1')");
        db.update(
                "INSERT INTO"
                    + " mes_produce_task(id,tenant_id,factory_code,code,task_code,route_code,process_route_code,status,notice_num,complete_num,quality_released_num,last_route)"
                    + " VALUES(21,1,'F1','P1','T1','R1','ROUTE1','PRODUCT',10,0,0,true)");
        db.update(
                "INSERT INTO"
                    + " mes_task_arrange(id,tenant_id,factory_code,code,task_code,produce_task_code,route_code,status,notice_num,complete_num,quality_released_num,un_pass_num,last_route,worker_code,mat_code,report_mode,route_type,worksite_types,actual_worksite_code)"
                    + " VALUES(11,1,'F1','A1','T1','P1','R1','PRODUCT',10,0,0,0,true,'worker','MAT1','QUANTITY','QUANTITY','MANUAL','WS1')");
    }

    private void effect(MesTaskReportVO p, String kind) {
        db.update(
                "INSERT INTO inventory_effect VALUES(?,?,?)",
                p.getReportId(),
                kind,
                p.getPassNum());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anExplicitNonePolicyDoesNotQuarantineNewExemptOutputOrHideARequiredInspection(
            boolean hasPendingInspection) {
        var gate =
                new MesQualityGateDecision()
                        .setAllowed(true)
                        .setPassed(false)
                        .setGateMode("NONE")
                        .setPhase("REPORT")
                        .setMessage("no ordinary inspection gate");
        if (hasPendingInspection)
            gate.setItems(
                    List.of(
                            new MesQualityGateItemDTO()
                                    .setInspectionId(700L)
                                    .setRecordId(900L)
                                    .setTaskCode("T1")
                                    .setProduceTaskCode("P1")
                                    .setInspectionType("MIDDLE")
                                    .setGateStatus("PENDING")));
        when(dependency(MesQualityGateService.class).checkReport(anyString())).thenReturn(gate);
        reports.report(good());
        assertEquals(
                hasPendingInspection ? "PENDING" : "NONE",
                db.queryForObject("SELECT quality_gate_status FROM mes_task_report", String.class));
        assertEquals(
                hasPendingInspection ? "PENDING" : "NOT_REQUIRED",
                db.queryForObject(
                        "SELECT quality_quantity_status FROM mes_task_report", String.class));
        assertEquals(
                0,
                db.queryForObject(
                                "SELECT quality_pending_num FROM mes_task_report", BigDecimal.class)
                        .compareTo(new BigDecimal(hasPendingInspection ? "2" : "0")));
        assertEquals(
                0,
                db.queryForObject(
                                "SELECT quality_released_num FROM mes_task_report",
                                BigDecimal.class)
                        .compareTo(new BigDecimal(hasPendingInspection ? "0" : "2")));
    }

    @Test
    void anAllowedReportAwaitingALaterInspectionDoesNotClaimTheGateAlreadyPassed() {
        when(dependency(MesQualityGateService.class).checkReport(anyString()))
                .thenReturn(
                        new MesQualityGateDecision()
                                .setAllowed(true)
                                .setPassed(false)
                                .setGateMode("BLOCK")
                                .setPhase("REPORT")
                                .setMessage("末检待生成"));
        reports.report(good());
        assertEquals(
                "PENDING",
                db.queryForObject("SELECT quality_gate_status FROM mes_task_report", String.class));
        assertEquals(
                "PENDING",
                db.queryForObject(
                        "SELECT quality_quantity_status FROM mes_task_report", String.class));
    }

    // 测试工具：消费真实Gate返回对象，检查新报工持久事实。
    void assertUnjudgedDecisionCreatesPendingOutput(MesQualityGateDecision decision) {
        when(dependency(MesQualityGateService.class).checkReport(anyString())).thenReturn(decision);
        reports.report(good());
        assertEquals(
                "PENDING",
                db.queryForObject(
                        "SELECT quality_quantity_status FROM mes_task_report", String.class));
        assertEquals(
                "PENDING",
                db.queryForObject("SELECT quality_gate_status FROM mes_task_report", String.class));
        assertEquals(
                0,
                db.queryForObject(
                                "SELECT quality_released_num FROM mes_task_report",
                                BigDecimal.class)
                        .compareTo(BigDecimal.ZERO));
    }

    private MesTaskReportVO good() {
        var p = new MesTaskReportVO();
        p.setRequestId("good_request_00000001");
        p.setArrangeCode("A1");
        p.setPassNum(new BigDecimal("2"));
        p.setOperatorCode("worker");
        p.setOperatorName("worker");
        return p;
    }

    private MesTaskReportUnqualifiedVO ng() {
        var p = new MesTaskReportUnqualifiedVO();
        p.setRequestId("ng_request_000000001");
        p.setArrangeCode("A1");
        p.setUnPassNum(new BigDecimal("2"));
        p.setUnqualifiedType("TYPE1");
        return p;
    }

    private void send(String channel, MesTaskReportVO p) {
        switch (channel) {
            case "MOBILE" -> reports.reportMobile(p);
            case "INTEGRATION" -> reports.reportIntegration(p);
            case "DEVICE" -> {
                db.update("UPDATE mes_task_arrange SET worksite_types='AUTO' WHERE id=11");
                p.setSourceEventId("device-event-1");
                p.setDeviceCode("D1");
                reports.reportAuto(p);
            }
            default -> reports.report(p);
        }
    }

    private int count(String table) {
        return db.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void unchanged() {
        assertEquals(0, count("mes_task_report"));
        assertEquals(0, count("mes_task_report_unqualified"));
        assertEquals(0, count("mes_task_report_record"));
        assertEquals(0, count("inventory_effect"));
        assertEquals(0, count("command_receipt"));
        assertEquals(0, count("pass_receipt"));
        assertEquals(
                0,
                BigDecimal.ZERO.compareTo(
                        db.queryForObject(
                                "SELECT complete_num FROM mes_produce_task WHERE id=21",
                                BigDecimal.class)));
    }

    @Test
    void liveParentsAcceptNormalGoodReportAndCommitExactlyOnce() {
        reports.report(good());
        assertEquals(1, count("mes_task_report"));
        assertEquals(1, count("mes_task_report_record"));
        assertEquals(2, count("inventory_effect"));
        assertEquals(1, count("command_receipt"));
        assertEquals(
                0,
                new BigDecimal("2")
                        .compareTo(
                                db.queryForObject(
                                        "SELECT complete_num FROM mes_produce_task WHERE id=21",
                                        BigDecimal.class)));
    }

    @Test
    void liveParentsAcceptAnNgDeclarationThroughTheRealQuantityUpdates() {
        reports.unqualified(ng());
        assertEquals(1, count("mes_task_report_unqualified"));
        assertEquals(1, count("mes_task_report_record"));
        assertEquals(
                0,
                new BigDecimal("2")
                        .compareTo(
                                db.queryForObject(
                                        "SELECT un_pass_num FROM mes_task WHERE id=1",
                                        BigDecimal.class)));
        assertEquals(0, count("inventory_effect"));
    }

    @ParameterizedTest
    @CsvSource({
        "STATION,CLOSE",
        "STATION,FINISH",
        "MOBILE,CLOSE",
        "MOBILE,FINISH",
        "INTEGRATION,CLOSE",
        "INTEGRATION,FINISH",
        "DEVICE,CLOSE",
        "DEVICE,FINISH"
    })
    void aTerminalMainTaskRejectsNewGoodReportsAcrossAllChannels(String channel, String status) {
        db.update("UPDATE mes_task SET status=? WHERE id=1", status);
        assertThrows(CheckedException.class, () -> send(channel, good()));
        unchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLOSE", "FINISH"})
    void aTerminalActualProcessRejectsNewGoodQuantity(String status) {
        db.update("UPDATE mes_produce_task SET status=? WHERE id=21", status);
        assertThrows(CheckedException.class, () -> reports.report(good()));
        unchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLOSE", "FINISH"})
    void aTerminalMainTaskRejectsNewNgDeclarations(String status) {
        db.update("UPDATE mes_task SET status=? WHERE id=1", status);
        assertThrows(CheckedException.class, () -> reports.unqualified(ng()));
        unchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLOSE", "FINISH"})
    void aTerminalProcessRejectsNewNgDeclarations(String status) {
        db.update("UPDATE mes_produce_task SET status=? WHERE id=21", status);
        assertThrows(CheckedException.class, () -> reports.unqualified(ng()));
        unchanged();
    }

    @Test
    void anotherFactorysSameProcessCodeCannotPoisonTheActualParentUpdate() {
        db.update(
                "INSERT INTO"
                    + " mes_produce_task(id,tenant_id,factory_code,code,task_code,route_code,status,notice_num,complete_num)"
                    + " VALUES(22,1,'F2','P1','T2','R9','CLOSE',100,99)");
        assertDoesNotThrow(() -> reports.report(good()));
        assertEquals(
                0,
                new BigDecimal("99")
                        .compareTo(
                                db.queryForObject(
                                        "SELECT complete_num FROM mes_produce_task WHERE id=22",
                                        BigDecimal.class)));
    }

    @Test
    void anAlreadyAcceptedGoodRequestStillReturnsBeforeCurrentTerminalStateChecks() {
        db.update("UPDATE mes_task SET status='CLOSE' WHERE id=1");
        replayId = 99L;
        assertDoesNotThrow(() -> reports.report(good()));
        unchanged();
    }

    @Test
    void anAlreadySavedNgRequestReplaysWithoutAddingCountsAfterClosure() {
        reports.unqualified(ng());
        db.update("UPDATE mes_task SET status='CLOSE' WHERE id=1");
        reports.unqualified(ng());
        assertEquals(1, count("mes_task_report_unqualified"));
        assertEquals(
                0,
                new BigDecimal("2")
                        .compareTo(
                                db.queryForObject(
                                        "SELECT un_pass_num FROM mes_task WHERE id=1",
                                        BigDecimal.class)));
    }

    private MesTaskReportVO routeReturn(String rootStatus) {
        approvedReturn = true;
        db.update("UPDATE mes_task SET status=? WHERE id=1", rootStatus);
        db.update("UPDATE mes_produce_task SET status='FINISH' WHERE id=21");
        db.update(
                "UPDATE mes_task_arrange SET"
                    + " status='FINISH',report_mode='ONE_CODE',route_type='ONE_CODE' WHERE id=11");
        var p = good();
        p.setPassNum(BigDecimal.ONE);
        p.setTransferCode("SN-1");
        return p;
    }

    @Test
    void anApprovedReturnKeepsItsFinishedParentExceptionWithoutNewProductionOrInventory() {
        assertDoesNotThrow(() -> reports.report(routeReturn("FINISH")));
        assertEquals(1, count("mes_task_report"));
        assertEquals(0, count("inventory_effect"));
        assertEquals(
                0,
                BigDecimal.ZERO.compareTo(
                        db.queryForObject(
                                "SELECT complete_num FROM mes_produce_task WHERE id=21",
                                BigDecimal.class)));
    }

    @Test
    void aClosedTaskCannotBeReactivatedThroughAnApprovedReturn() {
        var p = routeReturn("CLOSE");
        assertThrows(CheckedException.class, () -> reports.report(p));
        unchanged();
    }

    @ParameterizedTest
    @CsvSource({"ROOT,GOOD", "ROOT,NG", "PROCESS,GOOD", "PROCESS,NG"})
    void anUnknownParentStateCannotBeGuessedAsLive(String parent, String kind) {
        db.update(
                parent.equals("ROOT")
                        ? "UPDATE mes_task SET status='UNKNOWN' WHERE id=1"
                        : "UPDATE mes_produce_task SET status='UNKNOWN' WHERE id=21");
        assertThrows(
                CheckedException.class,
                () -> {
                    if (kind.equals("GOOD")) reports.report(good());
                    else reports.unqualified(ng());
                });
        unchanged();
    }

    @Test
    void ngRequiresAnExistingExactProcessRatherThanOnlyAResidualDispatch() {
        db.update("DELETE FROM mes_produce_task WHERE id=21");
        assertThrows(CheckedException.class, () -> reports.unqualified(ng()));
        assertEquals(0, count("mes_task_report_unqualified"));
        assertEquals(0, count("mes_task_report_record"));
    }

    @Test
    void aWrongFrozenRouteCannotBeUsedForNgAtTheSameProcessCode() {
        db.update("UPDATE mes_produce_task SET route_code='WRONG' WHERE id=21");
        assertThrows(CheckedException.class, () -> reports.unqualified(ng()));
        unchanged();
    }

    @Test
    void aClosedActualProcessAlsoBlocksTheApprovedReturnException() {
        var p = routeReturn("FINISH");
        db.update("UPDATE mes_produce_task SET status='CLOSE' WHERE id=21");
        assertThrows(CheckedException.class, () -> reports.report(p));
        unchanged();
    }

    @Test
    void theOriginalParentQualitySyncIsPreservedWhenAddingThisReportQuantity() {
        when(dependency(MesQualityQuantityService.class).reconcile(anyString(), any()))
                .thenAnswer(
                        call -> {
                            db.update(
                                    "UPDATE mes_produce_task SET quality_released_num=7 WHERE"
                                            + " id=21");
                            return call.getArgument(1);
                        });
        reports.report(good());
        assertEquals(
                0,
                new BigDecimal("9")
                        .compareTo(
                                db.queryForObject(
                                        "SELECT quality_released_num FROM mes_produce_task WHERE"
                                                + " id=21",
                                        BigDecimal.class)));
    }

    @Test
    void aLateReceiptFailureRollsBackReportsQuantityAndControlledInventoryTogether() {
        doThrow(new CheckedException("controlled receipt persistence failure"))
                .when(dependency(MesReportCommandService.class))
                .accept(any(), any());
        assertThrows(CheckedException.class, () -> reports.report(good()));
        unchanged();
        assertEquals(
                0,
                BigDecimal.ZERO.compareTo(
                        db.queryForObject(
                                "SELECT un_in_stock_num FROM mes_task WHERE id=1",
                                BigDecimal.class)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GOOD", "NG"})
    void anAmbiguousProcessCodeWithinThisFactoryCannotCreateNewProduction(String kind) {
        db.update(
                "INSERT INTO"
                    + " mes_produce_task(id,tenant_id,factory_code,code,task_code,route_code,status,notice_num,complete_num)"
                    + " VALUES(22,1,'F1','P1','T2','R9','CLOSE',100,99)");
        assertThrows(
                CheckedException.class,
                () -> {
                    if (kind.equals("GOOD")) reports.report(good());
                    else reports.unqualified(ng());
                });
        unchanged();
        assertEquals(
                0,
                new BigDecimal("99")
                        .compareTo(
                                db.queryForObject(
                                        "SELECT complete_num FROM mes_produce_task WHERE id=22",
                                        BigDecimal.class)));
    }

    private void priorQuantity(boolean terminal) {
        db.update(
                "UPDATE mes_task_arrange SET complete_num=7,quality_released_num=5,last_route=?"
                        + " WHERE id=11",
                terminal);
        db.update(
                "UPDATE mes_produce_task SET complete_num=7,quality_released_num=5,last_route=?"
                        + " WHERE id=21",
                terminal);
        db.update("UPDATE mes_task SET un_in_stock_num=? WHERE id=1", terminal ? 5 : 0);
        when(dependency(MesQualityQuantityService.class).reconcile(anyString(), any()))
                .thenAnswer(
                        call -> {
                            db.update(
                                    "UPDATE mes_task_arrange SET"
                                            + " quality_released_num=quality_released_num+2 WHERE"
                                            + " id=11");
                            db.update(
                                    "UPDATE mes_produce_task SET"
                                            + " quality_released_num=quality_released_num+2 WHERE"
                                            + " id=21");
                            if (terminal)
                                db.update(
                                        "UPDATE mes_task SET un_in_stock_num=un_in_stock_num+2"
                                                + " WHERE id=1");
                            return call.getArgument(1);
                        });
    }

    @Test
    void qualityReleaseAtANonTerminalNodeKeepsBothTheOldAndNewDispatchRelease() {
        priorQuantity(false);
        reports.report(good());
        assertEquals(
                0,
                new BigDecimal("9")
                        .compareTo(
                                db.queryForObject(
                                        "SELECT quality_released_num FROM mes_task_arrange WHERE"
                                                + " id=11",
                                        BigDecimal.class)));
        assertEquals(
                0,
                new BigDecimal("9")
                        .compareTo(
                                db.queryForObject(
                                        "SELECT complete_num FROM mes_task_arrange WHERE id=11",
                                        BigDecimal.class)));
    }

    @Test
    void qualityReleaseAtTheTerminalNodeAndThisReportBothReachTheCurrentStockTotal() {
        priorQuantity(true);
        assertDoesNotThrow(() -> reports.report(good()));
        for (String table : List.of("mes_task_arrange", "mes_produce_task"))
            assertEquals(
                    0,
                    new BigDecimal("9")
                            .compareTo(
                                    db.queryForObject(
                                            "SELECT quality_released_num FROM " + table,
                                            BigDecimal.class)));
        assertEquals(
                0,
                new BigDecimal("9")
                        .compareTo(
                                db.queryForObject(
                                        "SELECT un_in_stock_num FROM mes_task WHERE id=1",
                                        BigDecimal.class)));
    }

    private <T> T dependency(Class<T> type) {
        return type.cast(dependencies.computeIfAbsent(type, k -> mock(type)));
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
        var proxy =
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class[] {service},
                        (p, m, args) -> {
                            if (m.getName().equals("saveFrozenSource")) {
                                actual.insert(args[0]);
                                return null;
                            }
                            if (m.getName().equals("verifySource")) return null;
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
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(config, "parent-ddl"), type);
        var info = TableInfoHelper.getTableInfo(type);
        var ddl =
                new StringBuilder("CREATE TABLE ")
                        .append(info.getTableName())
                        .append("(id bigint primary key");
        for (var f : info.getFieldList()) {
            var t = f.getPropertyType();
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
    void clear() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        SecurityContextHolder.clearContext();
    }
}
