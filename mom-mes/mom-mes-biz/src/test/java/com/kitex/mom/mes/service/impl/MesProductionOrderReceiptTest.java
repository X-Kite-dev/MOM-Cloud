package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.mom.mes.entity.MesPlanWithdrawalReceiptEntity;
import com.kitex.mom.mes.entity.MesApsBatchAllocationEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.support.TransactionTemplate;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.mes.entity.MesProductionOrderEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesProductionOrderMapper;
import com.kitex.mom.mes.service.MesProductionOrderService;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 公开接收方法、实际MyBatis查询/插入及Spring事务。仅适配PG事务锁返回值，不证明PG锁或并发。
 */
class MesProductionOrderReceiptTest {
    private JdbcTemplate db;
    private MesProductionOrderMapper rows;
    private MesProductionOrderService service;
    private MesTaskReceiveDTO receive;
    private MesTaskEntity task;
    private final List<Boolean> lockTransactions = new ArrayList<>();
    private boolean failAfterInsert;
    private TransactionTemplate transaction;
    private CyclicBarrier concurrentReceivers;

    @BeforeEach
    void setup() throws Exception {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:production-order-receipt-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        db = new JdbcTemplate(source);
        var config = new MybatisConfiguration();
        var global = new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(new com.kitex.common.data.mybatis.MybatisPlusMetaObjectHandler());
        GlobalConfigUtils.setGlobalConfig(config, global);
        config.addMapper(MesProductionOrderMapper.class);
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new LongValue(TenantContextHolder.getTenantId());
            }
        }));
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(config);
        factory.setGlobalConfig(global);
        factory.setPlugins(interceptor);
        rows = new SqlSessionTemplate(factory.getObject()).getMapper(MesProductionOrderMapper.class);
        var table = TableInfoHelper.getTableInfo(MesProductionOrderEntity.class);
        var ddl = new StringBuilder("CREATE TABLE mes_production_order(id bigint primary key");
        for (var field : table.getFieldList()) {
            Class<?> type = field.getPropertyType();
            String sqlType = type == Long.class ? "bigint" : type == Integer.class ? "integer"
                    : type == BigDecimal.class ? "decimal(24,8)" : type == LocalDateTime.class ? "timestamp"
                    : "varchar(1024)";
            ddl.append(',').append(field.getColumn()).append(' ').append(sqlType);
            if (field.getColumn().equals("del_flag")) ddl.append(" default '0'");
        }
        db.execute(ddl.append(')').toString());
        db.execute("CREATE SCHEMA mom_mes");
        createFactTable(config, MesTaskEntity.class, "mes_task");
        createFactTable(config, MesPlanWithdrawalReceiptEntity.class, "mes_plan_withdrawal_receipt");
        createFactTable(config, MesApsBatchAllocationEntity.class, "mes_aps_batch_allocation");
        db.execute("CREATE TABLE receipt_lock(id int primary key)");
        db.update("INSERT INTO receipt_lock VALUES(1)");
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        var adapter = (MesProductionOrderMapper) Proxy.newProxyInstance(
                MesProductionOrderMapper.class.getClassLoader(), new Class<?>[]{MesProductionOrderMapper.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("lockReceivedOrder")) {
                        synchronized (lockTransactions) {
                            lockTransactions.add(TransactionSynchronizationManager.isActualTransactionActive());
                        }
                        if (concurrentReceivers != null) {
                            concurrentReceivers.await(5, TimeUnit.SECONDS);
                            db.queryForObject("SELECT id FROM receipt_lock WHERE id=1 FOR UPDATE", Integer.class);
                        }
                        return 1;
                    }
                    try {
                        Object result = method.invoke(rows, args);
                        if (method.getName().equals("insert") && failAfterInsert) {
                            throw new CheckedException("模拟插入后故障，必须回滚订单");
                        }
                        return result;
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
        // 本夹具限定订单级容量；合同逐行分配的真实链路由MesFormalPlanCapacityTest单独覆盖。
        var target = new MesProductionOrderServiceImpl(new MesProductionOrderCapacityGuard(adapter, source),
                org.mockito.Mockito.mock(MesFormalPlanAllocationService.class));
        ReflectionTestUtils.setField(target, "baseMapper", adapter);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        service = (MesProductionOrderService) proxy.getProxy();
        receive = new MesTaskReceiveDTO();
        receive.setFactoryCode("F1");
        receive.setProductionOrder("ORDER-1");
        receive.setFormalPlanContractId(21L);
        receive.setFormalPlanContractNo("CONTRACT-1");
        receive.setFormalPlanContractVersion(1);
        receive.setFormalPlanContractFingerprint("a".repeat(64));
        receive.setPlanningPolicyId(10L);
        receive.setPlanningPolicyVersion(1);
        receive.setPlanningPolicyFingerprint("b".repeat(64));
        receive.setMrpAuthority("MOM_MRP");
        receive.setProductionOrderSyncMode("MOM_ORDER_ONLY");
        receive.setSourceSystem("MOM");
        receive.setSourcePlanNo("MRP-1");
        receive.setSourcePlanVersion("1");
        receive.setMatCode("P1");
        receive.setMatName("产品");
        receive.setNoticeNum(BigDecimal.ONE);
        receive.setProductionOrderQuantity(new BigDecimal("2"));
        task = new MesTaskEntity().setCode("TASK-1").setFactoryCode("F1")
                .setPlanStartDate(LocalDateTime.of(2026, 10, 26, 8, 0))
                .setPlanEndDate(LocalDateTime.of(2026, 10, 26, 12, 0));
        task.setId(31L);
        capacityTask(31L, "TASK-1", "1", "CREATE", "0");
    }

    @AfterEach
    void cleanup() {
        db.execute("SHUTDOWN");
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void sameNumberInAnotherFactoryDoesNotBecomeTheCurrentOrder() {
        seed(11L, 1L, "F2");
        var result = assertDoesNotThrow(() -> service.recordReceivedOrder(receive, task));
        assertEquals("F1", result.getFactoryCode());
        assertNotEquals(11L, result.getId());
        assertEquals(2, count());
        assertEquals(1, db.queryForObject("select count(*) from mes_production_order where factory_code='F2'", Integer.class));
    }

    @Test
    void sameNumberInAnotherTenantDoesNotBecomeTheCurrentOrder() {
        seed(11L, 2L, "F1");
        var result = assertDoesNotThrow(() -> service.recordReceivedOrder(receive, task));
        assertNotEquals(11L, result.getId());
        assertEquals(2, count());
        assertEquals(1L, db.queryForObject("select tenant_id from mes_production_order where id=?", Long.class, result.getId()));
    }

    @Test
    void identicalSplitOrderCanBeReusedWithoutAddingOrChangingTheMaster() {
        seed(11L, 1L, "F1");
        assertEquals(11L, service.recordReceivedOrder(receive, task).getId());
        receive.setNoticeNum(new BigDecimal("0.5"));
        task.setPlanStartDate(task.getPlanStartDate().plusDays(1));
        task.setId(32L);
        task.setCode("TASK-2");
        capacityTask(32L, "TASK-2", "0.5", "CREATE", "0");
        assertEquals(11L, service.recordReceivedOrder(receive, task).getId());
        assertEquals(1, count());
        assertEquals(0, new BigDecimal("2").compareTo(
                db.queryForObject("select order_quantity from mes_production_order", BigDecimal.class)));
    }

    @Test
    void duplicateMastersInTheSameScopeCannotBeChosenWithLimitOne() {
        seed(11L, 1L, "F1");
        seed(12L, 1L, "F1");
        assertThrows(CheckedException.class, () -> service.recordReceivedOrder(receive, task));
        assertEquals(2, count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sourceSystem", "sourcePlanNo", "sourcePlanVersion", "planningPolicyId",
            "planningPolicyVersion", "planningPolicyFingerprint", "mrpAuthority", "erpProductionOrderNo",
            "sourceType", "sourceNo", "formalPlanContractNo", "formalPlanContractVersion",
            "formalPlanContractFingerprint", "productCode", "orderQuantity"})
    void changedFrozenOrderEvidenceCannotBeAdopted(String property) {
        var row = order(11L, 1L, "F1");
        var changed = new BeanWrapperImpl(row);
        Object value = changed.getPropertyValue(property);
        changed.setPropertyValue(property, value instanceof Long ? 99L : value instanceof Integer ? 99
                : value instanceof BigDecimal ? new BigDecimal("99") : "CHANGED");
        rows.insert(row);
        assertThrows(CheckedException.class, () -> service.recordReceivedOrder(receive, task));
        assertEquals(1, count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sourceSystem", "sourcePlanNo", "sourcePlanVersion", "planningPolicyId",
            "planningPolicyVersion", "planningPolicyFingerprint", "mrpAuthority", "sourceType", "sourceNo",
            "formalPlanContractNo", "formalPlanContractVersion", "formalPlanContractFingerprint",
            "productCode", "orderQuantity"})
    void missingFrozenEvidenceDoesNotMakeAnExistingMasterReusable(String property) {
        var row = order(11L, 1L, "F1");
        new BeanWrapperImpl(row).setPropertyValue(property, null);
        rows.insert(row);
        assertThrows(CheckedException.class, () -> service.recordReceivedOrder(receive, task));
        assertEquals(1, count());
    }

    @Test
    void anotherVerifiedRepresentativeLineDoesNotReplaceTheOrderMaster() {
        seed(11L, 1L, "F1");
        // 合同核验器负责确认另一MAKE行有效；订单主记录保留首批代表行，不能据此拒绝后续拆批。
        receive.setFormalPlanContractId(22L);
        assertEquals(11L, service.recordReceivedOrder(receive, task).getId());
        assertEquals(21L, db.queryForObject(
                "select formal_plan_contract_id from mes_production_order where id=11", Long.class));
        assertEquals(1, count());
    }

    @Test
    void matchingErpOrderEvidenceStillAllowsSplitReceipt() {
        var row = order(11L, 1L, "F1").setErpProductionOrderNo("ERP-1");
        rows.insert(row);
        receive.setProductionOrderSyncMode("ERP_ORDER_REQUIRED");
        receive.setErpProductionOrderNo("ERP-1");
        db.update("UPDATE mom_mes.mes_task SET erp_production_order_no='ERP-1',production_order_sync_mode='ERP_ORDER_REQUIRED' WHERE id=31");
        assertEquals(11L, service.recordReceivedOrder(receive, task).getId());
        assertEquals(1, count());
    }

    @Test
    void missingErpEvidenceCannotBeAddedToAnExistingLocalMaster() {
        seed(11L, 1L, "F1");
        receive.setProductionOrderSyncMode("ERP_ORDER_REQUIRED");
        receive.setErpProductionOrderNo("ERP-1");
        assertThrows(CheckedException.class, () -> service.recordReceivedOrder(receive, task));
        assertNull(db.queryForObject(
                "select erp_production_order_no from mes_production_order where id=11", String.class));
        assertEquals(1, count());
    }

    @Test
    void missingFactoryContextCannotWriteAnOrder() {
        FactoryContextHolder.clear();
        assertThrows(CheckedException.class, () -> service.recordReceivedOrder(receive, task));
        assertEquals(0, count());
    }

    @Test
    void anotherFactoryContextCannotWriteAnOrder() {
        FactoryContextHolder.setFactoryCode("F2");
        assertThrows(CheckedException.class, () -> service.recordReceivedOrder(receive, task));
        assertEquals(0, count());
    }

    @Test
    void missingOrderQuantityIsAControlledBusinessRejection() {
        receive.setProductionOrderQuantity(null);
        assertThrows(CheckedException.class, () -> service.recordReceivedOrder(receive, task));
        assertEquals(0, count());
    }

    @Test
    void orderLockRunsInsideTheServiceTransaction() {
        service.recordReceivedOrder(receive, task);
        assertEquals(List.of(true), lockTransactions);
    }

    @Test
    void failureAfterInsertRollsBackTheNewOrder() {
        failAfterInsert = true;
        assertThrows(CheckedException.class, () -> service.recordReceivedOrder(receive, task));
        assertEquals(0, count());
    }

    @Test
    void allSplitTasksTogetherCannotExceedTheOrderQuantity() {
        capacityTask(30L, "PRIOR", "1.1", "CREATE", "0");
        assertThrows(CheckedException.class, () -> service.recordReceivedOrder(receive, task));
        assertEquals(0, count());
    }

    @Test
    void replayOfTheSameSavedTaskDoesNotConsumeQuantityTwice() {
        capacityTask(30L, "PRIOR", "1", "CREATE", "0");
        service.recordReceivedOrder(receive, task);
        service.recordReceivedOrder(receive, task);
        assertEquals(1, count());
    }

    @ParameterizedTest
    @CsvSource({"FINISH,0", "CLOSE,0", "CREATE,1", "CLOSE,1"})
    void closingFinishingOrSoftDeletingDoesNotReleaseQuantity(String status, String deleted) {
        capacityTask(30L, "PRIOR", "2", status, deleted);
        assertThrows(CheckedException.class, () -> service.recordReceivedOrder(receive, task));
    }

    @ParameterizedTest
    @CsvSource({"1,F2", "2,F1"})
    void otherScopesDoNotConsumeThisOrder(long tenant, String factory) {
        capacityTask(30L, "PRIOR", "9", "CREATE", "0");
        db.update("UPDATE mom_mes.mes_task SET tenant_id=?,factory_code=? WHERE id=30", tenant, factory);
        assertDoesNotThrow(() -> service.recordReceivedOrder(receive, task));
    }

    @Test
    void currentTaskMustBeAnExistingMatchingReceiptFact() {
        db.update("DELETE FROM mom_mes.mes_task WHERE id=31");
        assertThrows(CheckedException.class, () -> service.recordReceivedOrder(receive, task));
    }

    @Test
    void currentSavedQuantityCannotBeChangedByTheRequest() {
        db.update("UPDATE mom_mes.mes_task SET notice_num=2 WHERE id=31");
        assertThrows(CheckedException.class, () -> service.recordReceivedOrder(receive, task));
    }

    @Test
    void genuineReworkDoesNotAddNewProductionSupply() {
        capacityTask(30L, "REWORK", "2", "CREATE", "0");
        db.update("UPDATE mom_mes.mes_task SET resource_type='REWORK',rework_request_id='RW-1',source_task_code='TASK-1' WHERE id=30");
        assertDoesNotThrow(() -> service.recordReceivedOrder(receive, task));
    }

    @Test
    void partialReworkMarkerCannotHideEvenASmallTask() {
        capacityTask(30L, "BROKEN-REWORK", "0.2", "CREATE", "0");
        db.update("UPDATE mom_mes.mes_task SET resource_type='REWORK' WHERE id=30");
        assertThrows(CheckedException.class, () -> service.recordReceivedOrder(receive, task));
    }

    @Test
    void aNormalReceiptCannotDisguiseItsCurrentTaskAsRework() {
        db.update("UPDATE mom_mes.mes_task SET resource_type='REWORK',rework_request_id='RW-1',source_task_code='OTHER' WHERE id=31");
        assertThrows(CheckedException.class, () -> service.recordReceivedOrder(receive, task));
    }

    @Test
    void verifiedWithdrawalReleasesExactlyItsClosedTasks() {
        withdrawnPrior("2", "PRIOR", 1);
        assertDoesNotThrow(() -> service.recordReceivedOrder(receive, task));
        assertEquals("CLOSE", db.queryForObject("SELECT status FROM mom_mes.mes_task WHERE id=30", String.class));
        assertEquals(1, count());
    }

    @Test
    void withdrawalCanIncludeAnotherOrderWithoutReleasingItsQuantityHere() {
        withdrawnPrior("2", "PRIOR,OTHER", 2);
        capacityTask(29L, "OTHER", "1", "CLOSE", "0");
        db.update("UPDATE mom_mes.mes_task SET production_order='ORDER-2' WHERE id=29");
        allocation(29L, "OTHER", "1");
        db.update("UPDATE mom_mes.mes_plan_withdrawal_receipt SET expected_quantity=3 WHERE id=40");
        assertDoesNotThrow(() -> service.recordReceivedOrder(receive, task));
    }

    @ParameterizedTest
    @ValueSource(strings={"missingAllocation", "wrongIssue", "wrongQuantity", "duplicateCode", "startedTask",
            "wrongFingerprint", "missingTime", "missingTask", "notClosed"})
    void incompleteWithdrawalProofCannotReleaseQuantity(String damage) {
        withdrawnPrior("2", "PRIOR", 1);
        switch (damage) {
            case "missingAllocation" -> db.update("DELETE FROM mom_mes.mes_aps_batch_allocation");
            case "wrongIssue" -> db.update("UPDATE mom_mes.mes_aps_batch_allocation SET issue_id=999");
            case "wrongQuantity" -> db.update("UPDATE mom_mes.mes_plan_withdrawal_receipt SET expected_quantity=1");
            case "duplicateCode" -> db.update("UPDATE mom_mes.mes_plan_withdrawal_receipt SET closed_task_codes='PRIOR,PRIOR',closed_task_count=2");
            case "startedTask" -> db.update("UPDATE mom_mes.mes_task SET start_time=CURRENT_TIMESTAMP WHERE id=30");
            case "wrongFingerprint" -> db.update("UPDATE mom_mes.mes_plan_withdrawal_receipt SET actual_evidence_fingerprint=?", "f".repeat(64));
            case "missingTime" -> db.update("UPDATE mom_mes.mes_plan_withdrawal_receipt SET handled_time=NULL");
            case "missingTask" -> db.update("UPDATE mom_mes.mes_plan_withdrawal_receipt SET closed_task_codes='PRIOR,UNKNOWN',closed_task_count=2");
            case "notClosed" -> db.update("UPDATE mom_mes.mes_task SET status='CREATE' WHERE id=30");
        }
        assertThrows(CheckedException.class, () -> service.recordReceivedOrder(receive, task));
        assertEquals(0, count());
    }

    @ParameterizedTest
    @CsvSource({"1,F2", "2,F1"})
    void anotherScopesWithdrawalDoesNotReleaseThisOrder(long tenant, String factory) {
        withdrawnPrior("2", "PRIOR", 1);
        db.update("UPDATE mom_mes.mes_plan_withdrawal_receipt SET tenant_id=?,factory_code=? WHERE id=40", tenant, factory);
        assertThrows(CheckedException.class, () -> service.recordReceivedOrder(receive, task));
    }

    @Test
    void twoSuccessfulWithdrawalsCannotReleaseTheSameTaskTwice() {
        withdrawnPrior("0.5", "PRIOR", 1);
        String columns = TableInfoHelper.getTableInfo(MesPlanWithdrawalReceiptEntity.class).getFieldList()
                .stream().map(field -> field.getColumn()).collect(java.util.stream.Collectors.joining(","));
        db.update("INSERT INTO mom_mes.mes_plan_withdrawal_receipt(id,"+columns+") SELECT 41,"
                + columns + " FROM mom_mes.mes_plan_withdrawal_receipt WHERE id=40");
        assertThrows(CheckedException.class, () -> service.recordReceivedOrder(receive, task));
    }

    @Test
    void capacityFailureRollsBackTheOuterTaskInsert() {
        capacityTask(30L, "PRIOR", "2", "CREATE", "0");
        assertThrows(CheckedException.class, () -> transaction.execute(status -> {
            capacityTask(32L, "NEW", "1", "CREATE", "0");
            var incomingTask = new MesTaskEntity().setCode("NEW").setFactoryCode("F1");
            incomingTask.setId(32L);
            return service.recordReceivedOrder(receive, incomingTask);
        }));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_mes.mes_task WHERE id=32", Integer.class));
        assertEquals(0, count());
    }

    @Test
    void concurrentSplitReceiptsCannotBothConsumeTheLastUnit() throws Exception {
        seed(11L, 1L, "F1");
        concurrentReceivers = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> receiveConcurrently(32L));
            var second = pool.submit(() -> receiveConcurrently(33L));
            assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
            assertEquals(2, db.queryForObject("SELECT count(*) FROM mom_mes.mes_task", Integer.class));
            assertEquals(1, count());
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(ints={java.sql.Connection.TRANSACTION_REPEATABLE_READ, java.sql.Connection.TRANSACTION_SERIALIZABLE})
    void staleSnapshotIsolationCannotApproveAnotherReceipt(int isolation) {
        transaction.setIsolationLevel(isolation);
        assertThrows(CheckedException.class, () -> transaction.execute(status ->
                service.recordReceivedOrder(receive, task)));
        assertEquals(0, count());
    }

    private int receiveConcurrently(long id) {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        try {
            transaction.execute(status -> {
                capacityTask(id, "TASK-"+id, "1", "CREATE", "0");
                var incomingTask = new MesTaskEntity().setCode("TASK-"+id).setFactoryCode("F1");
                incomingTask.setId(id);
                service.recordReceivedOrder(receive, incomingTask);
                return null;
            });
            return 1;
        } catch (CheckedException expected) {
            return 0;
        } finally {
            TenantContextHolder.clear();
            FactoryContextHolder.clear();
        }
    }

    private void createFactTable(MybatisConfiguration config, Class<?> type, String name) {
        var table = TableInfoHelper.initTableInfo(new MapperBuilderAssistant(config, name), type);
        var ddl = new StringBuilder("CREATE TABLE mom_mes."+name+"(id bigint primary key");
        for (var field : table.getFieldList()) {
            var javaType=field.getPropertyType();
            String sqlType=javaType==Long.class ? "bigint" : javaType==Integer.class ? "integer"
                    : javaType==BigDecimal.class ? "decimal(24,8)" : javaType==LocalDateTime.class ? "timestamp"
                    : javaType==Boolean.class ? "boolean" : "varchar(4096)";
            ddl.append(',').append(field.getColumn()).append(' ').append(sqlType);
        }
        db.execute(ddl.append(')').toString());
    }

    private void capacityTask(long id, String code, String quantity, String status, String deleted) {
        db.update("INSERT INTO mom_mes.mes_task(id,tenant_id,factory_code,code,production_order,notice_num,status,del_flag,"
                + "mat_code,formal_plan_contract_no,formal_plan_contract_version,formal_plan_contract_fingerprint,"
                + "planning_policy_id,planning_policy_version,planning_policy_fingerprint,mrp_authority,source_system,"
                + "source_plan_no,source_plan_version,production_order_sync_mode,plan_no) "
                + "VALUES(?,1,'F1',?,'ORDER-1',?,?,?,'P1','CONTRACT-1',1,?,10,1,?,'MOM_MRP','MOM','MRP-1','1','MOM_ORDER_ONLY','ISSUE-1')",
                id,code,new BigDecimal(quantity),status,deleted,"a".repeat(64),"b".repeat(64));
    }

    private void allocation(long taskId,String taskCode,String quantity) {
        db.update("INSERT INTO mom_mes.mes_aps_batch_allocation(id,tenant_id,factory_code,mes_task_id,mes_task_code,"
                + "issue_id,issue_no,issue_quantity_snapshot,allocated_quantity) VALUES(?,1,'F1',?,?,101,'ISSUE-1',3,?)",
                taskId+100,taskId,taskCode,new BigDecimal(quantity));
    }

    private void withdrawnPrior(String quantity,String codes,int number) {
        capacityTask(30L,"PRIOR",quantity,"CLOSE","0");
        allocation(30L,"PRIOR",quantity);
        db.update("INSERT INTO mom_mes.mes_plan_withdrawal_receipt(id,tenant_id,factory_code,request_id,request_fingerprint,"
                + "issue_id,issue_no,expected_quantity,expected_evidence_fingerprint,actual_evidence_fingerprint,"
                + "closed_task_count,closed_task_codes,closed_produce_task_count,closed_arrange_count,cancelled_qms_task_count,"
                + "qms_evidence_fingerprint,status,reason,message,handled_time) "
                + "VALUES(40,1,'F1','WD-1',?,101,'ISSUE-1',?,?,?, ?,?,0,0,0,?,'SUCCEEDED','测试撤回','已撤回',CURRENT_TIMESTAMP)",
                "c".repeat(64),new BigDecimal(quantity),"d".repeat(64),"d".repeat(64),number,codes,"e".repeat(64));
    }

    private int count() {
        return db.queryForObject("select count(*) from mes_production_order", Integer.class);
    }

    private void seed(long id, long tenant, String factory) {
        rows.insert(order(id, tenant, factory));
    }

    private MesProductionOrderEntity order(long id, long tenant, String factory) {
        var row = new MesProductionOrderEntity().setOrderNo("ORDER-1").setFactoryCode(factory)
                .setOrderType("STANDARD").setSourceType("PLAN").setSourceNo("CONTRACT-1")
                .setFormalPlanContractId(21L).setFormalPlanContractNo("CONTRACT-1")
                .setFormalPlanContractVersion(1).setFormalPlanContractFingerprint("a".repeat(64))
                .setSourceSystem("MOM").setSourcePlanNo("MRP-1").setSourcePlanVersion("1")
                .setPlanningPolicyId(10L).setPlanningPolicyVersion(1).setPlanningPolicyFingerprint("b".repeat(64))
                .setMrpAuthority("MOM_MRP").setProductCode("P1")
                .setOrderQuantity(new BigDecimal("2")).setRemainingQuantity(new BigDecimal("2"))
                .setFinishedQuantity(BigDecimal.ZERO).setStatus("SCHEDULED");
        row.setId(id);
        row.setTenantId(tenant);
        row.setDelFlag("0");
        return row;
    }
}
