package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryPlanningPolicyService;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.MesProductionOrderMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.mapper.MesApsBatchAllocationMapper;
import com.kitex.mom.mes.mapper.MesTaskIssueRecoveryMapper;
import com.kitex.mom.mes.service.MesTaskService;
import com.kitex.mom.mes.service.MesProductionOrderService;
import com.kitex.mom.plan.dto.PlnFormalPlanContractDTO;
import com.kitex.mom.plan.feign.RemotePlanFormalContractService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 实际公开订单登记、MyBatis物理事实及Spring事务；仅PG咨询锁适配为本地锁。 */
class MesFormalPlanCapacityTest {
    private JdbcTemplate db;
    private TransactionTemplate transaction;
    private MesProductionOrderService service;
    private MesTaskService taskService;
    private MesTaskIssueRecoveryService recoveries;
    private PlnFormalPlanContractDTO contract;
    private MesFormalPlanReceiptVerifier verifier;
    private MesTaskEntity lastTask;
    private long sequence;
    private CyclicBarrier concurrentReceivers;

    @BeforeEach
    void setup() throws Exception {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var source = new DriverManagerDataSource("jdbc:h2:mem:formal-capacity-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        db = new JdbcTemplate(source);
        db.execute("CREATE SCHEMA mom_mes");
        db.execute("CREATE TABLE contract_lock(id int primary key)");
        db.update("INSERT INTO contract_lock VALUES(1)");
        var config = new MybatisConfiguration();
        var global = new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(new com.kitex.common.data.mybatis.MybatisPlusMetaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(config, global);
        config.addMapper(MesProductionOrderMapper.class);
        config.addMapper(MesTaskMapper.class);
        config.addMapper(MesApsBatchAllocationMapper.class);
        config.addMapper(MesTaskIssueRecoveryMapper.class);
        createTable(config, MesProductionOrderEntity.class, "mes_production_order");
        db.execute("CREATE SYNONYM mom_mes.mes_production_order FOR PUBLIC.mes_production_order");
        createTable(config, MesTaskEntity.class, "mom_mes.mes_task");
        db.execute("CREATE SYNONYM mes_task FOR mom_mes.mes_task");
        createTable(config, MesPlanWithdrawalReceiptEntity.class, "mom_mes.mes_plan_withdrawal_receipt");
        createTable(config, MesApsBatchAllocationEntity.class, "mom_mes.mes_aps_batch_allocation");
        db.execute("CREATE SYNONYM mes_aps_batch_allocation FOR mom_mes.mes_aps_batch_allocation");
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(config);
        factory.setGlobalConfig(global);
        var interceptors = new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
        interceptors.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor(
                new com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler() {
                    @Override
                    public net.sf.jsqlparser.expression.Expression getTenantId() {
                        return new net.sf.jsqlparser.expression.LongValue(TenantContextHolder.getTenantId());
                    }
                }));
        factory.setPlugins(interceptors);
        var sessions = new SqlSessionTemplate(factory.getObject());
        var actual = sessions.getMapper(MesProductionOrderMapper.class);
        var adapter = (MesProductionOrderMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{MesProductionOrderMapper.class}, (proxy, method, args) -> {
                    if (method.getName().startsWith("lock")) {
                        if (method.getName().equals("lockFormalContract") && concurrentReceivers != null) {
                            concurrentReceivers.await(5, TimeUnit.SECONDS);
                            db.queryForObject("SELECT id FROM contract_lock WHERE id=1 FOR UPDATE", Integer.class);
                        }
                        return 1;
                    }
                    try { return method.invoke(actual, args); }
                    catch (InvocationTargetException ex) { throw ex.getCause(); }
                });
        var manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        var capacity = new MesProductionOrderCapacityGuard(adapter, source);
        contractPorts();
        var target = new MesProductionOrderServiceImpl(capacity,
                new MesFormalPlanAllocationService(adapter, capacity, verifier, new com.fasterxml.jackson.databind.ObjectMapper()));
        ReflectionTestUtils.setField(target, "baseMapper", adapter);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = (MesProductionOrderService) proxy.getProxy();
        taskService = actualTaskService(sessions, manager);
        var access = mock(MesActualFactoryAccessService.class);
        when(access.require("write")).thenReturn("F1");
        var recovery = new MesTaskIssueRecoveryService(sessions.getMapper(MesTaskIssueRecoveryMapper.class),
                (MesTaskMapper) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{MesTaskMapper.class},
                        (o,m,args) -> m.getName().equals("acquireApsBatchTransactionLock") ? 1 : m.invoke(sessions.getMapper(MesTaskMapper.class),args)),
                access, capacity);
        var recoveryProxy = new ProxyFactory(recovery);
        recoveryProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        recoveries = (MesTaskIssueRecoveryService) recoveryProxy.getProxy();
    }

    /** 不替代add/save/订单登记/合同分配；MDM工程取包及序号是可控外部端口。 */
    private MesTaskService actualTaskService(SqlSessionTemplate sessions, DataSourceTransactionManager manager) throws Exception {
        var actual = sessions.getMapper(MesTaskMapper.class);
        var mapper = (MesTaskMapper) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{MesTaskMapper.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("acquireApsBatchTransactionLock")) return 1;
                    try { return method.invoke(actual, args); }
                    catch (InvocationTargetException ex) { throw ex.getCause(); }
                });
        var ctor = MesTaskServiceImpl.class.getDeclaredConstructors()[0];
        var target = (MesTaskServiceImpl) ctor.newInstance(new Object[ctor.getParameterCount()]);
        ReflectionTestUtils.setField(target, "baseMapper", mapper);
        ReflectionTestUtils.setField(target, "formalPlanReceiptVerifier", verifier);
        ReflectionTestUtils.setField(target, "productionOrderService", service);
        ReflectionTestUtils.setField(target, "apsBatchAllocationMapper", sessions.getMapper(MesApsBatchAllocationMapper.class));
        var engineering = mock(MesEngineeringPackageService.class);
        var bundle = new com.kitex.mom.mdm.dto.MdmRouteEngineeringDTO.ProductionPackage();
        bundle.setReleaseId(901L); bundle.setContentHash("e".repeat(64)); bundle.setVersionNo(1);
        bundle.setRouteId(902L); bundle.setRouteCode("R1"); bundle.setBomId(903L); bundle.setBomVersion("B1");
        when(engineering.resolve(any())).thenReturn(new MesEngineeringPackageService.FrozenEngineering(
                bundle, "[]", "[]", "{}", "{}"));
        ReflectionTestUtils.setField(target, "engineeringPackages", engineering);
        var numbers = mock(com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService.class);
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        when(numbers.create("mes_task")).thenAnswer(call -> R.ok("ADD-TASK-" + counter.incrementAndGet(), "测试序号"));
        ReflectionTestUtils.setField(target, "remoteSeqConfigService", numbers);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (MesTaskService) proxy.getProxy();
    }

    private void contractPorts() {
        var policy = new MdmFactoryPlanningPolicyDTO();
        policy.setId("10"); policy.setVersionNo(1); policy.setFactoryCode("F1");
        policy.setContentFingerprint("a".repeat(64)); policy.setMrpAuthority("MOM_MRP");
        policy.setProductionOrderSyncMode("MOM_ORDER_ONLY");
        contract = new PlnFormalPlanContractDTO();
        contract.setContractNo("C1"); contract.setContractVersion(1);
        contract.setVerificationStatus("VERIFIED"); contract.setContractStatus("PUBLISHED");
        contract.setSourceSystemCode("MOM"); contract.setSourcePlanNo("MRP-1"); contract.setSourcePlanVersion("1");
        contract.setPlanningPolicyId("10"); contract.setPlanningPolicyVersion(1);
        contract.setPlanningPolicyFingerprint("a".repeat(64)); contract.setSourceContentFingerprint("b".repeat(64));
        contract.setLines(List.of(line("21", "L1", "D1", "2"), line("22", "L2", "D1", "3"),
                line("23", "L3", "D2", "20")));
        var policies = mock(RemoteMdmFactoryPlanningPolicyService.class);
        var contracts = mock(RemotePlanFormalContractService.class);
        when(policies.active("F1")).thenReturn(R.ok(policy));
        when(contracts.verified("C1", 1)).thenReturn(R.ok(contract));
        verifier = new MesFormalPlanReceiptVerifier(policies, contracts);
    }

    private PlnFormalPlanContractDTO.Line line(String id, String no, String demand, String qty) {
        var row = new PlnFormalPlanContractDTO.Line();
        row.setId(id); row.setLineNo(no); row.setSourceDemandNo(demand); row.setSupplyType("MAKE");
        row.setProductCode("P1"); row.setUnitCode("EA"); row.setPlannedQuantity(new BigDecimal(qty));
        row.setContentFingerprint("c".repeat(64));
        return row;
    }

    private void createTable(MybatisConfiguration config, Class<?> type, String name) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(config, "capacity-ddl"), type);
        var ddl = new StringBuilder("CREATE TABLE ").append(name).append("(id bigint primary key");
        for (var field : TableInfoHelper.getTableInfo(type).getFieldList()) {
            Class<?> t = field.getPropertyType();
            String sql = t == Long.class ? "bigint" : t == Integer.class ? "integer"
                    : t == BigDecimal.class ? "decimal(24,8)" : t == LocalDateTime.class ? "timestamp"
                    : "varchar(64000)";
            ddl.append(',').append(field.getColumn()).append(' ').append(sql);
            if (field.getColumn().equals("del_flag")) ddl.append(" default '0'");
        }
        db.execute(ddl.append(')').toString());
    }

    private MesTaskReceiveDTO command(String order, String qty) {
        var cmd = new MesTaskReceiveDTO();
        cmd.setFactoryCode("F1"); cmd.setProductionOrder(order); cmd.setPlanNo("ISSUE-" + order);
        cmd.setMatCode("P1"); cmd.setNoticeNum(new BigDecimal(qty)); cmd.setProductionOrderQuantity(new BigDecimal(qty));
        cmd.setFormalPlanContractId(21L); cmd.setFormalPlanContractNo("C1"); cmd.setFormalPlanContractVersion(1);
        cmd.setFormalPlanContractFingerprint("b".repeat(64)); cmd.setPlanningPolicyId(10L);
        cmd.setPlanningPolicyVersion(1); cmd.setPlanningPolicyFingerprint("a".repeat(64));
        cmd.setMrpAuthority("MOM_MRP"); cmd.setProductionOrderSyncMode("MOM_ORDER_ONLY");
        cmd.setSourceSystem("MOM"); cmd.setSourcePlanNo("MRP-1"); cmd.setSourcePlanVersion("1");
        cmd.setEngineeringReleaseId(901L); cmd.setEngineeringContentHash("e".repeat(64)); cmd.setEngineeringVersionNo(1);
        return com.kitex.mom.mes.dto.MesTaskReceiveRequestSigner.seal(cmd, "REQUEST-" + (++sequence));
    }

    private MesTaskEntity persist(MesTaskReceiveDTO cmd) {
        var task = new MesTaskEntity();
        cn.hutool.core.bean.BeanUtil.copyProperties(cmd, task);
        long requestSequence = Long.parseLong(cmd.getSourceRequestId().substring("REQUEST-".length()));
        task.setId(100L + requestSequence); task.setTenantId(1L); task.setFactoryCode("F1");
        task.setCode("TASK-" + requestSequence); task.setStatus("CREATE"); task.setDelFlag("0");
        db.update("INSERT INTO mom_mes.mes_task(id,tenant_id,factory_code,code,production_order,plan_no,mat_code,notice_num,"
                + "status,del_flag,formal_plan_contract_id,formal_plan_contract_no,formal_plan_contract_version,"
                + "formal_plan_contract_fingerprint,planning_policy_id,planning_policy_version,planning_policy_fingerprint,"
                + "mrp_authority,production_order_sync_mode,source_system,source_plan_no,source_plan_version,source_request_id,source_request_fingerprint) "
                + "VALUES(?,1,'F1',?,?,?,'P1',?,'CREATE','0',?,'C1',1,?,10,1,?,'MOM_MRP','MOM_ORDER_ONLY','MOM','MRP-1','1',?,?)",
                task.getId(), task.getCode(), cmd.getProductionOrder(), cmd.getPlanNo(), cmd.getNoticeNum(), cmd.getFormalPlanContractId(),
                "b".repeat(64), "a".repeat(64), cmd.getSourceRequestId(), cmd.getSourceRequestFingerprint());
        return lastTask = task;
    }

    private void accept(String order, String qty) {
        var cmd = command(order, qty);
        transaction.executeWithoutResult(status -> service.recordReceivedOrder(cmd, persist(cmd)));
    }

    private String allocation(MesTaskEntity task) {
        var bean = new BeanWrapperImpl(task);
        assertTrue(bean.isReadableProperty("formalPlanAllocationSnapshot"), "工单必须持久化逐合同明细的分配快照");
        String saved = db.queryForObject("SELECT formal_plan_allocation_snapshot FROM mom_mes.mes_task WHERE id=?",
                String.class, task.getId());
        assertNotNull(saved);
        return saved;
    }

    @AfterEach
    void cleanup() { TenantContextHolder.clear(); FactoryContextHolder.clear(); }

    @Test
    void fiveUnitsAreAllocatedAsTwoAndThreeWithoutRewritingContract() {
        accept("ORDER-1", "5");
        var json = cn.hutool.json.JSONUtil.parseObj(allocation(lastTask));
        var lines = json.getJSONArray("lines");
        assertEquals(2, lines.size());
        assertEquals("21", lines.getJSONObject(0).getStr("lineId"));
        assertEquals(0, new BigDecimal("2").compareTo(lines.getJSONObject(0).getBigDecimal("quantity")));
        assertEquals("22", lines.getJSONObject(1).getStr("lineId"));
        assertEquals(0, new BigDecimal("3").compareTo(lines.getJSONObject(1).getBigDecimal("quantity")));
        assertEquals(0, new BigDecimal("2").compareTo(contract.getLines().get(0).getPlannedQuantity()));
    }

    @Test
    void anotherOrderCannotReuseAlreadyConsumedContractQuantity() {
        accept("ORDER-1", "4");
        assertThrows(CheckedException.class, () -> accept("ORDER-2", "3"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mom_mes.mes_task", Integer.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mes_production_order", Integer.class));
    }

    @Test
    void softDeletingOldTaskDoesNotReleaseContractQuantity() {
        accept("ORDER-1", "5");
        db.update("UPDATE mom_mes.mes_task SET del_flag='1'");
        assertThrows(CheckedException.class, () -> accept("ORDER-2", "1"));
    }

    @Test
    void manuallyClosingOldTaskDoesNotReleaseContractQuantity() {
        accept("ORDER-1", "5");
        db.update("UPDATE mom_mes.mes_task SET status='CLOSE'");
        assertThrows(CheckedException.class, () -> accept("ORDER-2", "1"));
    }

    @Test
    void legacyTaskWithoutSnapshotConsumesOnlyItsActualRepresentativeLine() {
        var legacy = command("LEGACY", "2");
        transaction.executeWithoutResult(status -> persist(legacy));
        accept("ORDER-2", "3");
        var lines = cn.hutool.json.JSONUtil.parseObj(allocation(lastTask)).getJSONArray("lines");
        assertEquals(1, lines.size());
        assertEquals("22", lines.getJSONObject(0).getStr("lineId"));
    }

    @Test
    void malformedLegacyOverageCannotBorrowOtherPathsAfterUpgrade() {
        var legacy = command("LEGACY", "3");
        transaction.executeWithoutResult(status -> persist(legacy));
        assertThrows(CheckedException.class, () -> accept("ORDER-2", "1"));
    }

    @Test
    void otherDemandDoesNotOccupyThisDemandContractLines() {
        var other = command("OTHER", "20"); other.setFormalPlanContractId(23L);
        transaction.executeWithoutResult(status -> persist(other));
        assertDoesNotThrow(() -> accept("ORDER-2", "5"));
        allocation(lastTask);
    }

    @Test
    void replayingSameSavedTaskKeepsAllocationAndDoesNotTakeQuantityTwice() {
        var cmd = command("ORDER-1", "5");
        transaction.executeWithoutResult(status -> {
            var task = persist(cmd);
            service.recordReceivedOrder(cmd, task);
            String first = allocation(task);
            service.recordReceivedOrder(cmd, task);
            assertEquals(first, allocation(task));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"version", "representative", "fingerprint", "policy", "request"})
    void damagedPriorIdentityCannotBecomeAvailableQuantity(String damage) {
        accept("ORDER-1", "2");
        switch (damage) {
            case "version" -> db.update("UPDATE mom_mes.mes_task SET formal_plan_contract_version=NULL");
            case "representative" -> db.update("UPDATE mom_mes.mes_task SET formal_plan_contract_id=NULL");
            case "fingerprint" -> db.update("UPDATE mom_mes.mes_task SET formal_plan_contract_fingerprint='bad'");
            case "policy" -> db.update("UPDATE mom_mes.mes_task SET planning_policy_id=99");
            case "request" -> db.update("UPDATE mom_mes.mes_task SET source_request_fingerprint='bad'");
            default -> fail(damage);
        }
        assertThrows(CheckedException.class, () -> accept("ORDER-2", "1"));
    }

    @Test
    void removingNewSnapshotMustNotReinterpretItAsLegacy() {
        accept("ORDER-1", "2");
        assertTrue(new BeanWrapperImpl(lastTask).isReadableProperty("formalPlanAllocationVersion"),
                "新旧分配必须有持久模型标记，不能仅凭快照NULL猜测历史");
        db.update("UPDATE mom_mes.mes_task SET formal_plan_allocation_snapshot=NULL");
        assertThrows(CheckedException.class, () -> accept("ORDER-2", "1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"version", "taskId", "demand", "unit", "lineId", "lineFingerprint", "quantity", "duplicate", "malformed"})
    void damagedAllocationSnapshotCannotReleaseOrMoveQuantity(String damage) {
        accept("ORDER-1", "2");
        var value = cn.hutool.json.JSONUtil.parseObj(allocation(lastTask));
        var line = value.getJSONArray("lines").getJSONObject(0);
        switch (damage) {
            case "version" -> value.set("schemaVersion", 99);
            case "taskId" -> value.set("taskId", "999");
            case "demand" -> value.set("sourceDemandNo", "D2");
            case "unit" -> value.set("unitCode", "KG");
            case "lineId" -> line.set("lineId", "23");
            case "lineFingerprint" -> line.set("fingerprint", "d".repeat(64));
            case "quantity" -> line.set("quantity", BigDecimal.ONE);
            case "duplicate" -> value.getJSONArray("lines").add(line);
            case "malformed" -> { }
            default -> fail(damage);
        }
        db.update("UPDATE mom_mes.mes_task SET formal_plan_allocation_snapshot=?",
                damage.equals("malformed") ? "broken" : value.toString());
        assertThrows(CheckedException.class, () -> accept("ORDER-2", "1"));
    }

    private void withdraw(MesTaskEntity task, String qty) {
        db.update("UPDATE mom_mes.mes_task SET status='CLOSE' WHERE id=?", task.getId());
        db.update("INSERT INTO mom_mes.mes_aps_batch_allocation(id,tenant_id,factory_code,mes_task_id,mes_task_code,"
                + "issue_id,issue_no,issue_quantity_snapshot,allocated_quantity) VALUES(501,1,'F1',?,?,201,'ISSUE-1',?,?)",
                task.getId(),task.getCode(),new BigDecimal(qty),new BigDecimal(qty));
        db.update("INSERT INTO mom_mes.mes_plan_withdrawal_receipt(id,tenant_id,factory_code,request_id,request_fingerprint,"
                + "issue_id,issue_no,expected_quantity,expected_evidence_fingerprint,actual_evidence_fingerprint,closed_task_count,"
                + "closed_task_codes,closed_produce_task_count,closed_arrange_count,cancelled_qms_task_count,qms_evidence_fingerprint,"
                + "status,reason,message,handled_time) VALUES(601,1,'F1','WD-1',?,201,'ISSUE-1',?,?,?,1,?,0,0,0,?,"
                + "'SUCCEEDED','测试撤回','已撤回',CURRENT_TIMESTAMP)", "d".repeat(64),new BigDecimal(qty),"e".repeat(64),
                "e".repeat(64),task.getCode(),"f".repeat(64));
    }

    @Test
    void completeSuccessfulWithdrawalReleasesExactLineAllocations() {
        accept("ORDER-1", "5");
        withdraw(lastTask, "5");
        assertDoesNotThrow(() -> accept("ORDER-2", "5"));
        allocation(lastTask);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "fingerprint", "count", "quantity", "execution", "allocation", "repeatedCode"})
    void incompleteWithdrawalCannotFreeContractQuantity(String damage) {
        accept("ORDER-1", "5");
        withdraw(lastTask, "5");
        switch (damage) {
            case "pending" -> db.update("UPDATE mom_mes.mes_plan_withdrawal_receipt SET status='EXECUTING'");
            case "fingerprint" -> db.update("UPDATE mom_mes.mes_plan_withdrawal_receipt SET actual_evidence_fingerprint='bad'");
            case "count" -> db.update("UPDATE mom_mes.mes_plan_withdrawal_receipt SET closed_task_count=2");
            case "quantity" -> db.update("UPDATE mom_mes.mes_plan_withdrawal_receipt SET expected_quantity=4");
            case "execution" -> db.update("UPDATE mom_mes.mes_task SET complete_num=1");
            case "allocation" -> db.update("DELETE FROM mom_mes.mes_aps_batch_allocation");
            case "repeatedCode" -> db.update("UPDATE mom_mes.mes_plan_withdrawal_receipt SET closed_task_codes=closed_task_codes||','||closed_task_codes");
            default -> fail(damage);
        }
        assertThrows(CheckedException.class, () -> accept("ORDER-2", "1"));
    }

    @Test
    void allocationAndOrderRollBackWithOuterFailure() {
        var cmd = command("ORDER-1", "5");
        assertThrows(CheckedException.class, () -> transaction.executeWithoutResult(status -> {
            service.recordReceivedOrder(cmd, persist(cmd));
            allocation(lastTask);
            throw new CheckedException("模拟后续失败");
        }));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_mes.mes_task", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM mes_production_order", Integer.class));
        assertDoesNotThrow(() -> accept("ORDER-2", "5"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"requestId", "requestFingerprint"})
    void newAllocationRequiresNonemptyFrozenRequestIdentity(String missing) {
        var cmd = command("ORDER-1", "1");
        assertThrows(CheckedException.class, () -> transaction.executeWithoutResult(status -> {
            var task = persist(cmd);
            if (missing.equals("requestId")) {
                cmd.setSourceRequestId(null);
                db.update("UPDATE mom_mes.mes_task SET source_request_id=NULL");
            } else {
                cmd.setSourceRequestFingerprint(null);
                db.update("UPDATE mom_mes.mes_task SET source_request_fingerprint=NULL");
            }
            service.recordReceivedOrder(cmd, task);
        }));
    }

    @Test
    void partialBatchReservesItsFrozenWholeOrderAgainstAnotherOrder() {
        var first = command("ORDER-1", "1"); first.setProductionOrderQuantity(new BigDecimal("5"));
        transaction.executeWithoutResult(status -> service.recordReceivedOrder(first, persist(first)));
        assertThrows(CheckedException.class, () -> accept("ORDER-2", "1"));
    }

    @Test
    void partialWithdrawalReleasesOnlyItsProvenShareOfWholeOrder() {
        var first = command("ORDER-1", "2"); first.setProductionOrderQuantity(new BigDecimal("5"));
        transaction.executeWithoutResult(status -> service.recordReceivedOrder(first, persist(first)));
        withdraw(lastTask, "2");
        assertThrows(CheckedException.class, () -> accept("TOO-MUCH", "3"));
        assertDoesNotThrow(() -> accept("ORDER-2", "2"));
    }

    @Test
    void oldTasksWithoutOrderMasterCanJoinTheirSameFrozenOrder() {
        var legacy = command("ORDER-1", "2");
        transaction.executeWithoutResult(status -> persist(legacy));
        var next = command("ORDER-1", "3"); next.setProductionOrderQuantity(new BigDecimal("5"));
        assertDoesNotThrow(() -> transaction.executeWithoutResult(status -> service.recordReceivedOrder(next, persist(next))));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mes_production_order", Integer.class));
    }

    @Test
    void receivingAgainAfterWithdrawalReacquiresWholeOrderCommitment() {
        accept("ORDER-1", "5"); withdraw(lastTask, "5");
        var resumed = command("ORDER-1", "2"); resumed.setProductionOrderQuantity(new BigDecimal("5"));
        assertDoesNotThrow(() -> transaction.executeWithoutResult(status -> service.recordReceivedOrder(resumed, persist(resumed))));
        assertThrows(CheckedException.class, () -> accept("ORDER-2", "3"));
    }

    @Test
    void actualAddSavesAllocationAndReplaysOriginalFingerprint() {
        var cmd = command("ORDER-1", "5"); cmd.setMatSpec("SPEC");
        com.kitex.mom.mes.dto.MesTaskReceiveRequestSigner.seal(cmd, cmd.getSourceRequestId());
        String fingerprint = cmd.getSourceRequestFingerprint();
        var receipt = taskService.add(cmd);
        assertFalse(receipt.getReplayed());
        String snapshot = db.queryForObject("SELECT formal_plan_allocation_snapshot FROM mom_mes.mes_task WHERE id=?",
                String.class, receipt.getTaskId());
        assertNotNull(snapshot);
        var replay = taskService.add(cmd);
        assertTrue(replay.getReplayed());
        assertEquals(receipt.getTaskId(), replay.getTaskId());
        assertEquals(fingerprint, replay.getRequestFingerprint());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mom_mes.mes_task", Integer.class));
        assertEquals(snapshot, db.queryForObject("SELECT formal_plan_allocation_snapshot FROM mom_mes.mes_task WHERE id=?",
                String.class, receipt.getTaskId()));
    }

    @Test
    void actualAddRollsBackTaskWhenAnotherOrderHasConsumedContract() {
        var first = command("ORDER-1", "4"); first.setMatSpec("SPEC");
        com.kitex.mom.mes.dto.MesTaskReceiveRequestSigner.seal(first, first.getSourceRequestId());
        taskService.add(first);
        var next = command("ORDER-2", "3"); next.setMatSpec("SPEC");
        com.kitex.mom.mes.dto.MesTaskReceiveRequestSigner.seal(next, next.getSourceRequestId());
        assertThrows(CheckedException.class, () -> taskService.add(next));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mom_mes.mes_task", Integer.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mes_production_order", Integer.class));
    }

    @Test
    void actualApsSplitBatchesShareFiveAndSaveTheirSourceLedger() {
        for (int batch = 1; batch <= 2; batch++) {
            String qty = batch == 1 ? "2" : "3";
            var cmd = command("ORDER-1", qty); cmd.setMatSpec("SPEC");
            cmd.setProductionOrderQuantity(new BigDecimal("5"));
            cmd.setApsTaskId(801L); cmd.setApsBatchId(810L + batch); cmd.setApsBatchNo("BATCH-" + batch);
            cmd.setApsBatchType("SPLIT"); cmd.setApsBatchFingerprint("f".repeat(64));
            cmd.setApsBatchAllocations(List.of(new com.kitex.mom.mes.dto.MesApsBatchAllocationDTO()
                    .setIssueId(201L).setIssueNo("ISSUE-1").setIssueQuantitySnapshot(new BigDecimal("5"))
                    .setAllocationSeq(1).setAllocatedQuantity(new BigDecimal(qty))));
            com.kitex.mom.mes.dto.MesTaskReceiveRequestSigner.seal(cmd, cmd.getSourceRequestId());
            assertFalse(taskService.add(cmd).getReplayed());
        }
        assertEquals(2, db.queryForObject("SELECT count(*) FROM mom_mes.mes_aps_batch_allocation", Integer.class));
        assertEquals(0, new BigDecimal("5").compareTo(db.queryForObject(
                "SELECT sum(allocated_quantity) FROM mom_mes.mes_aps_batch_allocation", BigDecimal.class)));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mes_production_order", Integer.class));
        assertEquals(0, new BigDecimal("5").compareTo(db.queryForObject(
                "SELECT order_quantity FROM mes_production_order", BigDecimal.class)));
    }

    @Test
    void otherTenantCannotOccupyCapacityOrProvideRequestReplay() {
        var cmd = command("ORDER-1", "5"); cmd.setMatSpec("SPEC");
        com.kitex.mom.mes.dto.MesTaskReceiveRequestSigner.seal(cmd, cmd.getSourceRequestId());
        var old = taskService.add(cmd);
        db.update("UPDATE mom_mes.mes_task SET tenant_id=2");
        db.update("UPDATE mes_production_order SET tenant_id=2");
        var current = taskService.add(cmd);
        assertFalse(current.getReplayed());
        assertNotEquals(old.getTaskId(), current.getTaskId());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mom_mes.mes_task WHERE tenant_id=1", Integer.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mom_mes.mes_task WHERE tenant_id=2", Integer.class));
    }

    @Test
    void concurrentOrdersCannotBothConsumeThreeOfFive() throws Exception {
        concurrentReceivers = new CyclicBarrier(2);
        var commands = List.of(command("ORDER-1", "3"), command("ORDER-2", "3"));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = commands.stream().map(cmd -> pool.submit(() -> {
                TenantContextHolder.setTenantId(1L); FactoryContextHolder.setFactoryCode("F1");
                try {
                    transaction.executeWithoutResult(status -> service.recordReceivedOrder(cmd, persist(cmd)));
                    return true;
                } catch (CheckedException expected) {
                    return false;
                } finally { TenantContextHolder.clear(); FactoryContextHolder.clear(); }
            })).toList();
            int accepted = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) accepted++;
            assertEquals(1, accepted);
            assertEquals(1, db.queryForObject("SELECT count(*) FROM mom_mes.mes_task", Integer.class));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void recoveringOldReceiptDoesNotRewriteItsFrozenQuantityFingerprintOrAllocation() {
        var legacy = command("ERP-OLD", "2"); legacy.setMatSpec("SPEC");
        com.kitex.mom.mes.dto.MesTaskReceiveRequestSigner.seal(legacy, legacy.getSourceRequestId());
        var original = taskService.add(legacy);
        String snapshot = db.queryForObject("SELECT formal_plan_allocation_snapshot FROM mom_mes.mes_task", String.class);
        var full = cn.hutool.core.bean.BeanUtil.copyProperties(legacy, MesTaskReceiveDTO.class);
        full.setProductionOrderQuantity(new BigDecimal("5"));
        com.kitex.mom.mes.dto.MesTaskReceiveRequestSigner.seal(full, full.getSourceRequestId());
        var restored = recoveries.recover(List.of(full, legacy));
        assertEquals(original.getTaskId(), restored.getTaskId());
        assertEquals(original.getRequestFingerprint(), restored.getRequestFingerprint());
        assertEquals(db.queryForObject("SELECT create_time FROM mom_mes.mes_task", LocalDateTime.class), restored.getAcceptedTime());
        assertTrue(restored.getReplayed());
        assertEquals(0, new BigDecimal("2").compareTo(db.queryForObject("SELECT order_quantity FROM mes_production_order",BigDecimal.class)));
        assertEquals(snapshot, db.queryForObject("SELECT formal_plan_allocation_snapshot FROM mom_mes.mes_task",String.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mom_mes.mes_task",Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"fingerprint", "orderQuantity", "notice", "product", "plan", "factory", "closed", "deleted", "orderDeleted", "orderMissing", "missingStatus", "unknownStatus"})
    void recoveryCannotAdoptChangedPayloadOrRetiredFact(String damage) {
        var cmd = command("ORDER-1", "2"); cmd.setMatSpec("SPEC");
        com.kitex.mom.mes.dto.MesTaskReceiveRequestSigner.seal(cmd, cmd.getSourceRequestId());
        taskService.add(cmd);
        switch (damage) {
            case "fingerprint" -> cmd.setSourceRequestFingerprint("a".repeat(64));
            case "orderQuantity" -> cmd.setProductionOrderQuantity(new BigDecimal("5"));
            case "notice" -> cmd.setNoticeNum(BigDecimal.ONE);
            case "product" -> cmd.setMatCode("P2");
            case "plan" -> cmd.setPlanNo("OTHER");
            case "factory" -> cmd.setFactoryCode("OTHER");
            case "closed" -> db.update("UPDATE mom_mes.mes_task SET status='CLOSE'");
            case "deleted" -> db.update("UPDATE mom_mes.mes_task SET del_flag='1'");
            case "orderDeleted" -> db.update("UPDATE mes_production_order SET del_flag='1'");
            case "orderMissing" -> db.update("DELETE FROM mes_production_order");
            case "missingStatus" -> db.update("UPDATE mom_mes.mes_task SET status=NULL");
            case "unknownStatus" -> db.update("UPDATE mom_mes.mes_task SET status='UNKNOWN'");
            default -> fail(damage);
        }
        assertThrows(CheckedException.class, () -> recoveries.recover(List.of(cmd)));
    }

    @Test
    void recoveryReturnsUnobservedWithoutCreatingTaskOrOrder() {
        var cmd = command("ORDER-1", "2");
        assertNull(recoveries.recover(List.of(cmd)));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_mes.mes_task",Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM mes_production_order",Integer.class));
    }

    @Test
    void recoverySelectsCurrentFullPayloadWhenBothVersionsAreOffered() {
        var full = command("ORDER-1", "5"); full.setMatSpec("SPEC");
        com.kitex.mom.mes.dto.MesTaskReceiveRequestSigner.seal(full, full.getSourceRequestId());
        var original = taskService.add(full);
        var legacy = cn.hutool.core.bean.BeanUtil.copyProperties(full, MesTaskReceiveDTO.class);
        legacy.setProductionOrderQuantity(new BigDecimal("2"));
        com.kitex.mom.mes.dto.MesTaskReceiveRequestSigner.seal(legacy, legacy.getSourceRequestId());
        var restored = recoveries.recover(List.of(legacy, full));
        assertEquals(original.getTaskId(), restored.getTaskId());
        assertEquals(full.getSourceRequestFingerprint(), restored.getRequestFingerprint());
        assertEquals(0, new BigDecimal("5").compareTo(db.queryForObject("SELECT order_quantity FROM mes_production_order",BigDecimal.class)));
    }
}
