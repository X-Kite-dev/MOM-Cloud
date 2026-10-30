package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.mapper.MesFactoryPlanningImpactMapper;
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
public class MesFactoryPlanningImpactTest {

    private JdbcTemplate db;

    private MesFactoryPlanningImpactInternalController controller;

    @BeforeEach
    void setup() throws Exception {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var source = new DriverManagerDataSource("jdbc:h2:mem:impact-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        db = new JdbcTemplate(source);
        db.execute("CREATE SCHEMA mom_mes");
        table("mes_task", "status varchar(64), planning_policy_id bigint, formal_plan_contract_id bigint, formal_plan_contract_no varchar(64), formal_plan_contract_version integer, formal_plan_contract_fingerprint varchar(64), planning_policy_version integer, planning_policy_fingerprint varchar(64)");
        table("mes_production_order", "planning_policy_id bigint, formal_plan_contract_id bigint, formal_plan_contract_no varchar(64), formal_plan_contract_version integer, formal_plan_contract_fingerprint varchar(64), planning_policy_version integer, planning_policy_fingerprint varchar(64)");
        table("mes_erp_posting_outbox", "status varchar(64)");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(MesFactoryPlanningImpactMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(MesFactoryPlanningImpactMapper.class);
        controller = new MesFactoryPlanningImpactInternalController(mapper);
    }

    private void table(String name, String columns) {
        db.execute("CREATE TABLE mom_mes." + name + "(tenant_id bigint,factory_code varchar(64),del_flag varchar(1)," + columns + ")");
    }

    @AfterEach
    void cleanup() {
        if (db != null)
            db.execute("SHUTDOWN");
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void legacyTasksStaySeparateFromFormalReferencesAndErpFailures() {
        db.update("INSERT INTO mom_mes.mes_task(tenant_id,factory_code,del_flag,status,planning_policy_id,formal_plan_contract_id,formal_plan_contract_no) VALUES(1,'F1','0','PRODUCT',NULL,NULL,NULL),(2,'F1','0','PRODUCT',NULL,NULL,NULL),(1,'F2','0','PRODUCT',NULL,NULL,NULL)");
        db.update("INSERT INTO mom_mes.mes_erp_posting_outbox VALUES(1,'F1','0','DEAD_LETTER'),(1,'F1','0','TRANSFERRED'),(2,'F1','0','PENDING')");
        var impact = controller.current("F1").getData();
        assertEquals(1L, impact.getUnfinishedTaskCount());
        assertEquals(0L, impact.getFormalSourceCount());
        assertEquals(1L, impact.getPendingErpEventCount());
        assertEquals(1L, impact.getTenantId());
    }

    @Test
    void completedDeletedTaskAndUnissuedOrderAreFormalReferences() {
        db.update("INSERT INTO mom_mes.mes_task(tenant_id,factory_code,del_flag,status,planning_policy_id,formal_plan_contract_id,formal_plan_contract_no) VALUES(1,'F1','1','FINISH',10,NULL,NULL),(2,'F1','0','FINISH',10,NULL,NULL)");
        db.update("INSERT INTO mom_mes.mes_production_order(tenant_id,factory_code,del_flag,planning_policy_id,formal_plan_contract_id,formal_plan_contract_no) VALUES(1,'F1','0',NULL,20,NULL),(1,'F2','0',10,NULL,NULL)");
        var impact = controller.current("F1").getData();
        assertEquals(0L, impact.getUnfinishedTaskCount());
        assertEquals(2L, impact.getFormalSourceCount());
    }

    @Test
    void nullStatusAndDeletedPendingPostingAreNotInvisible() {
        db.update("INSERT INTO mom_mes.mes_task(tenant_id,factory_code,del_flag,status,planning_policy_id,formal_plan_contract_id,formal_plan_contract_no) VALUES(1,'F1','0',NULL,NULL,NULL,NULL)");
        db.update("INSERT INTO mom_mes.mes_erp_posting_outbox VALUES(1,'F1','1','PENDING')");
        var impact = controller.current("F1").getData();
        assertEquals(1L, impact.getUnfinishedTaskCount());
        assertEquals(1L, impact.getPendingErpEventCount());
    }

    @Test
    void partialFormalMetadataIsNotMistakenForLegacy() {
        db.update("INSERT INTO mom_mes.mes_task(tenant_id,factory_code,del_flag,status,planning_policy_version) VALUES(1,'F1','1','FINISH',1)");
        db.update("INSERT INTO mom_mes.mes_production_order(tenant_id,factory_code,del_flag,formal_plan_contract_fingerprint) VALUES(1,'F1','0','partial')");
        assertEquals(2L, controller.current("F1").getData().getFormalSourceCount());
    }

    @Test
    void missingMandatoryPostingTableMustNotBeTreatedAsZero() {
        db.execute("DROP TABLE mom_mes.mes_erp_posting_outbox");
        assertThrows(RuntimeException.class, () -> controller.current("F1"));
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
