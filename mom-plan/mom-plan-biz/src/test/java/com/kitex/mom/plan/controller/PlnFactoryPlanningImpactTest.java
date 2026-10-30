package com.kitex.mom.plan.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.plan.mapper.PlnFactoryPlanningImpactMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实MyBatis SQL/H2验证计数范围；原生PG与内部认证链另作运行验收。
 */
public class PlnFactoryPlanningImpactTest {

    private JdbcTemplate db;

    private PlnFactoryPlanningImpactInternalController controller;

    @BeforeEach
    void setup() throws Exception {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var source = new DriverManagerDataSource("jdbc:h2:mem:impact-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        db = new JdbcTemplate(source);
        db.execute("CREATE SCHEMA mom_plan");
        table("plan_mat_mrp", "status boolean");
        table("pln_scheduling_task_detail", "lock_status boolean");
        table("pln_scheduling_operation", "lock_status boolean");
        table("pln_scheduling_batch", "lock_status boolean");
        table("pln_issue", "lock_status boolean, lock_task_id bigint");
        table("pln_formal_plan_contract", "contract_status varchar(64)");
        table("pln_erp_integration_outbox", "status varchar(64)");
        db.execute("CREATE ALIAS to_regclass FOR 'com.kitex.mom.plan.controller.PlnFactoryPlanningImpactTest.relation'");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(PlnFactoryPlanningImpactMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(PlnFactoryPlanningImpactMapper.class);
        controller = new PlnFactoryPlanningImpactInternalController(mapper);
    }

    private void table(String name, String columns) {
        db.execute("CREATE TABLE mom_plan." + name + "(tenant_id bigint,factory_code varchar(64),del_flag varchar(1)," + columns + ")");
    }

    @AfterEach
    void cleanup() {
        if (db != null)
            db.execute("SHUTDOWN");
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    /**
     * 仅目录探测适配H2；不代替实际PostgreSQL目录/权限验收。
     */
    public static String relation(java.sql.Connection connection, String name) throws Exception {
        try (var query = connection.prepareStatement("SELECT count(*) FROM information_schema.tables WHERE lower(table_schema||'.'||table_name)=?")) {
            query.setString(1, name);
            try (var rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1) > 0 ? name : null;
            }
        }
    }

    @Test
    void absentOptionalApsDoesNotHideOrdinaryLocksOrPendingErp() {
        db.update("INSERT INTO mom_plan.plan_mat_mrp VALUES(1,'F1','0',false),(2,'F1','0',false),(1,'F2','0',false),(1,'F1','1',false)");
        db.update("INSERT INTO mom_plan.pln_scheduling_task_detail VALUES(1,'F1','0',true)");
        db.update("INSERT INTO mom_plan.pln_scheduling_operation VALUES(2,'F1','0',true)");
        db.update("INSERT INTO mom_plan.pln_erp_integration_outbox VALUES(1,'F1','0','DEAD_LETTER'),(1,'F1','0','SUCCEEDED'),(2,'F1','0','PENDING')");
        var impact = controller.current("F1").getData();
        assertTrue(impact.getAvailable());
        assertFalse(impact.getIntelligentApsInstalled());
        assertEquals(1L, impact.getActiveMrpCount());
        assertEquals(1L, impact.getLockedApsCount());
        assertEquals(1L, impact.getPendingErpEventCount());
    }

    @Test
    void completedAndSoftDeletedFormalContractsStillPreventFirstActivation() {
        db.update("INSERT INTO mom_plan.pln_formal_plan_contract VALUES(1,'F1','1','CLOSED'),(2,'F1','0','ACTIVE'),(1,'F2','0','ACTIVE')");
        assertEquals(1L, controller.current("F1").getData().getFormalSourceCount());
    }

    @Test
    void bothIntelligentTablesInstalledIncludeUnfinishedCasesAndFailedDispatch() {
        table("pln_aps_case", "case_status varchar(64), planning_policy_id bigint, planning_policy_version integer, planning_policy_fingerprint varchar(64), formal_plan_contract_id bigint, formal_plan_contract_no varchar(64), formal_plan_contract_version integer, formal_plan_contract_fingerprint varchar(64)");
        table("pln_aps_mes_dispatch_outbox", "status varchar(64)");
        db.update("INSERT INTO mom_plan.pln_aps_case(tenant_id,factory_code,del_flag,case_status) VALUES(1,'F1','0','RELEASED'),(1,'F1','0','DRAFT'),(1,'F1','0',NULL),(2,'F1','0','RELEASED')");
        db.update("INSERT INTO mom_plan.pln_aps_mes_dispatch_outbox VALUES(1,'F1','0','DEAD_LETTER'),(1,'F1','0','SUCCEEDED')");
        var impact = controller.current("F1").getData();
        assertTrue(impact.getIntelligentApsInstalled());
        assertEquals(2L, impact.getLockedApsCount());
        assertEquals(1L, impact.getPendingErpEventCount());
    }

    @Test
    void softDeletionDoesNotReleaseAnExecutionLockOrActiveCase() {
        table("pln_aps_case", "case_status varchar(64), planning_policy_id bigint, planning_policy_version integer, planning_policy_fingerprint varchar(64), formal_plan_contract_id bigint, formal_plan_contract_no varchar(64), formal_plan_contract_version integer, formal_plan_contract_fingerprint varchar(64)");
        table("pln_aps_mes_dispatch_outbox", "status varchar(64)");
        db.update("INSERT INTO mom_plan.pln_scheduling_batch VALUES(1,'F1','1',true)");
        db.update("INSERT INTO mom_plan.pln_aps_case(tenant_id,factory_code,del_flag,case_status) VALUES(1,'F1','1','RELEASED')");
        assertEquals(2L, controller.current("F1").getData().getLockedApsCount());
    }

    @Test
    void terminalDeletedApsPartialFormalReferencePreventsFirstActivation() {
        table("pln_aps_case", "case_status varchar(64), planning_policy_id bigint, planning_policy_version integer, planning_policy_fingerprint varchar(64), formal_plan_contract_id bigint, formal_plan_contract_no varchar(64), formal_plan_contract_version integer, formal_plan_contract_fingerprint varchar(64)");
        table("pln_aps_mes_dispatch_outbox", "status varchar(64)");
        db.update("INSERT INTO mom_plan.pln_aps_case(tenant_id,factory_code,del_flag,case_status,planning_policy_version) VALUES(1,'F1','1','COMPLETED',1)");
        var result = controller.current("F1").getData();
        assertEquals(0L, result.getLockedApsCount());
        assertEquals(1L, result.getFormalSourceCount());
    }

    @Test
    void partialOptionalInstallationMustFail() {
        table("pln_aps_case", "case_status varchar(64), planning_policy_id bigint, planning_policy_version integer, planning_policy_fingerprint varchar(64), formal_plan_contract_id bigint, formal_plan_contract_no varchar(64), formal_plan_contract_version integer, formal_plan_contract_fingerprint varchar(64)");
        assertThrows(CheckedException.class, () -> controller.current("F1"));
    }

    @Test
    void missingMandatoryErpTableMustNotBeTreatedAsZero() {
        db.execute("DROP TABLE mom_plan.pln_erp_integration_outbox");
        assertThrows(RuntimeException.class, () -> controller.current("F1"));
    }

    @Test
    void pendingSoftDeletedErpAndNullMrpStatusRemainVisible() {
        db.update("INSERT INTO mom_plan.plan_mat_mrp VALUES(1,'F1','0',NULL)");
        db.update("INSERT INTO mom_plan.pln_erp_integration_outbox VALUES(1,'F1','1','PENDING')");
        var impact = controller.current("F1").getData();
        assertEquals(1L, impact.getActiveMrpCount());
        assertEquals(1L, impact.getPendingErpEventCount());
    }

    @ParameterizedTest
    @ValueSource(strings = { "NO_TENANT", "TENANT_SKIP", "FACTORY_SKIP", "OTHER_FACTORY" })
    void mustUseRealTenantAndFactory(String invalid) {
        switch(invalid) {
            case "NO_TENANT" ->
                TenantContextHolder.clear();
            case "TENANT_SKIP" ->
                TenantContextHolder.setTenantSkip();
            case "FACTORY_SKIP" ->
                FactoryContextHolder.setFactorySkip();
            default ->
                FactoryContextHolder.setFactoryCode("F2");
        }
        assertThrows(CheckedException.class, () -> controller.current("F1"));
    }
}
