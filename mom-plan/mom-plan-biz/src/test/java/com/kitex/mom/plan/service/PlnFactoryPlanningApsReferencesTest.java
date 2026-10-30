package com.kitex.mom.plan.service;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.plan.mapper.PlnFactoryPlanningApsReferenceMapper;
import com.kitex.mom.plan.mapper.PlnFactoryPlanningImpactMapper;
import com.kitex.mom.plan.service.impl.PlnFactoryPlanningApsReferencesService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.UUID;

/** Actual mapper/transaction; H2 catalog adapter is not a PostgreSQL certification. */
class PlnFactoryPlanningApsReferencesTest {
    JdbcTemplate db;
    PlnFactoryPlanningApsReferencesService service;

    @BeforeEach
    void setup() throws Exception {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:refs-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ds.setDriverClassName("org.h2.Driver");
        db = new JdbcTemplate(ds);
        db.execute("CREATE SCHEMA mom_plan");
        table("pln_scheduling_task", "task_no varchar(64),task_status varchar(64)");
        table(
                "pln_issue",
                "issue_no varchar(64),product_code varchar(64),status varchar(64),lock_status"
                        + " boolean,lock_task_id bigint");
        table(
                "pln_scheduling_task_detail",
                "task_id bigint,issue_id bigint,issue_no varchar(64),product_code"
                        + " varchar(64),status varchar(64),lock_status boolean");
        table(
                "pln_scheduling_operation",
                "task_id bigint,issue_id bigint,issue_no varchar(64),product_code"
                        + " varchar(64),schedule_status varchar(64),lock_status boolean");
        table(
                "pln_scheduling_batch",
                "task_id bigint,batch_no varchar(64),product_code varchar(64),batch_status"
                        + " varchar(64),lock_status boolean");
        db.execute(
                "CREATE ALIAS to_regclass FOR"
                        + " 'com.kitex.mom.plan.controller.PlnFactoryPlanningImpactTest.relation'");
        var conf = new MybatisConfiguration();
        conf.setMapUnderscoreToCamelCase(true);
        conf.addMapper(PlnFactoryPlanningApsReferenceMapper.class);
        conf.addMapper(PlnFactoryPlanningImpactMapper.class);
        var f = new MybatisSqlSessionFactoryBean();
        f.setDataSource(ds);
        f.setConfiguration(conf);
        var sessions = new SqlSessionTemplate(f.getObject());
        var target =
                new PlnFactoryPlanningApsReferencesService(
                        sessions.getMapper(PlnFactoryPlanningApsReferenceMapper.class),
                        sessions.getMapper(PlnFactoryPlanningImpactMapper.class));
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(
                new TransactionInterceptor(
                        new DataSourceTransactionManager(ds),
                        new AnnotationTransactionAttributeSource()));
        service = (PlnFactoryPlanningApsReferencesService) proxy.getProxy();
    }

    void table(String name, String cols) {
        db.execute(
                "CREATE TABLE mom_plan."
                        + name
                        + "(id bigint,tenant_id bigint,factory_code varchar(64),del_flag"
                        + " varchar(1),"
                        + cols
                        + ")");
    }

    void task(long id, long tenant, String factory) {
        db.update(
                "INSERT INTO mom_plan.pln_scheduling_task VALUES(?,?,?,'0',?,'SUCCESS')",
                id,
                tenant,
                factory,
                "TASK-" + id);
    }

    void issue(
            long id,
            long tenant,
            String factory,
            String deleted,
            String status,
            Boolean lock,
            Long task) {
        db.update(
                "INSERT INTO mom_plan.pln_issue VALUES(?,?,?,?,?,'P1',?,?,?)",
                id,
                tenant,
                factory,
                deleted,
                "PLAN-" + id,
                status,
                lock,
                task);
    }

    void optional(boolean dispatch) {
        table("pln_aps_case", "case_no varchar(64),case_status varchar(64)");
        if (dispatch) table("pln_aps_mes_dispatch_outbox", "status varchar(64)");
    }

    @AfterEach
    void close() {
        db.execute("SHUTDOWN");
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void listsFourOrdinaryFactTypesWithoutChangingHistory() {
        task(10, 1, "F1");
        issue(20, 1, "F1", "0", "1", true, 10L);
        db.update(
                "INSERT INTO mom_plan.pln_scheduling_task_detail"
                        + " VALUES(30,1,'F1','0',10,20,'PLAN-20','P1','0',true)");
        db.update(
                "INSERT INTO mom_plan.pln_scheduling_operation"
                        + " VALUES(40,1,'F1','0',10,20,'PLAN-20','P1','LOCKED',true)");
        db.update(
                "INSERT INTO mom_plan.pln_scheduling_batch"
                        + " VALUES(50,1,'F1','0',10,'BATCH-50','P1','LOCKED',true)");
        var result = service.read("F1");
        assertEquals(4L, result.getTotal());
        assertEquals(4, result.getRecords().size());
        assertFalse(result.getTruncated());
        assertEquals(
                java.util.Set.of("PLAN", "TASK_DETAIL", "OPERATION", "BATCH"),
                result.getRecords().stream()
                        .map(r -> r.getSourceType())
                        .collect(java.util.stream.Collectors.toSet()));
        var plan =
                result.getRecords().stream()
                        .filter(r -> r.getSourceType().equals("PLAN"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("20", plan.getPlanId());
        assertEquals("PLAN-20", plan.getPlanNo());
        assertEquals("1", plan.getPlanStatus());
        assertEquals("TASK-10", plan.getTaskNo());
        assertEquals("SUCCESS", plan.getTaskStatus());
        assertFalse(plan.getRelationIncomplete());
        assertTrue(
                db.queryForObject(
                        "SELECT lock_status FROM mom_plan.pln_issue WHERE id=20", Boolean.class));
        assertEquals(
                "1",
                db.queryForObject(
                        "SELECT status FROM mom_plan.pln_issue WHERE id=20", String.class));
    }

    @Test
    void keepsSoftDeletedFactsAndFlagsOrphanReferences() {
        issue(20, 1, "F1", "1", "0", true, 999L);
        var result = service.read("F1");
        assertEquals(1L, result.getTotal());
        var row = result.getRecords().get(0);
        assertTrue(row.getSourceDeleted());
        assertTrue(row.getRelationIncomplete());
        assertNull(row.getTaskNo());
    }

    @Test
    void crossFactoryAndTenantJoinsCannotExposeOtherIdentifiers() {
        task(10, 2, "F1");
        issue(20, 1, "F2", "0", "0", false, null);
        db.update(
                "INSERT INTO mom_plan.pln_scheduling_task_detail"
                        + " VALUES(30,1,'F1','0',10,20,'OWN-SNAPSHOT','P1','0',true)");
        issue(21, 2, "F1", "0", "0", true, 10L);
        issue(22, 1, "F2", "0", "0", true, 10L);
        var result = service.read("F1");
        assertEquals(1L, result.getTotal());
        var row = result.getRecords().get(0);
        assertEquals("OWN-SNAPSHOT", row.getPlanNo());
        assertNull(row.getTaskNo());
        assertTrue(row.getRelationIncomplete());
    }

    @Test
    void referenceAloneStillCountsAndUnlockedRowsAreNotInvented() {
        task(10, 1, "F1");
        issue(20, 1, "F1", "0", "0", false, 10L);
        issue(21, 1, "F1", "0", "0", false, null);
        assertEquals(1L, service.read("F1").getTotal());
    }

    @Test
    void returnsBoundedDeterministicSampleAndFullCount() {
        task(10, 1, "F1");
        for (long id = 100; id < 153; id++) issue(id, 1, "F1", "0", "0", true, 10L);
        var first = service.read("F1");
        var second = service.read("F1");
        assertEquals(53L, first.getTotal());
        assertEquals(50, first.getRecords().size());
        assertTrue(first.getTruncated());
        assertEquals(first.getRecords(), second.getRecords());
        assertNotNull(first.getObservedAt());
    }

    @Test
    void optionalCasesUseSameActivePredicateAndRetainDeletedCases() {
        optional(true);
        db.update(
                "INSERT INTO mom_plan.pln_aps_case"
                    + " VALUES(1,1,'F1','1','CASE-1','RELEASED'),(2,1,'F1','0','CASE-2',NULL),(3,1,'F1','0','CASE-3','COMPLETED'),(4,2,'F1','0','CASE-4','RELEASED')");
        var result = service.read("F1");
        assertEquals(2L, result.getTotal());
        assertEquals(
                java.util.List.of("1", "2"),
                result.getRecords().stream().map(r -> r.getSourceId()).toList());
        assertEquals("CASE-1", result.getRecords().get(0).getSourceNo());
        assertTrue(result.getRecords().get(0).getSourceDeleted());
    }

    @Test
    void optionalCaseSampleUsesTheSameTextIdentityOrderAsTheMergedSample() {
        optional(true);
        for (long id = 1; id <= 51; id++)
            db.update(
                    "INSERT INTO mom_plan.pln_aps_case VALUES(?,1,'F1','0',?,'RELEASED')",
                    id,
                    "CASE-" + id);
        var result = service.read("F1");
        assertEquals(51L, result.getTotal());
        assertTrue(result.getRecords().stream().anyMatch(r -> r.getSourceId().equals("51")));
        assertFalse(result.getRecords().stream().anyMatch(r -> r.getSourceId().equals("9")));
    }

    @Test
    void emptyIsAnExplicitSuccessfulSnapshot() {
        var result = service.read("F1");
        assertEquals(0L, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
        assertFalse(result.getTruncated());
    }

    @Test
    void partialInstallationAndSqlFailureAreNotEmptySnapshots() {
        optional(false);
        assertThrows(CheckedException.class, () -> service.read("F1"));
        db.execute("DROP TABLE mom_plan.pln_aps_case");
        db.execute("DROP TABLE mom_plan.pln_scheduling_batch");
        assertThrows(RuntimeException.class, () -> service.read("F1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_TENANT", "TENANT_SKIP", "FACTORY_SKIP", "OTHER_FACTORY"})
    void requiresActualScope(String mode) {
        switch (mode) {
            case "NO_TENANT" -> TenantContextHolder.clear();
            case "TENANT_SKIP" -> TenantContextHolder.setTenantSkip();
            case "FACTORY_SKIP" -> FactoryContextHolder.setFactorySkip();
            default -> FactoryContextHolder.setFactoryCode("F2");
        }
        assertThrows(CheckedException.class, () -> service.read("F1"));
    }
}
