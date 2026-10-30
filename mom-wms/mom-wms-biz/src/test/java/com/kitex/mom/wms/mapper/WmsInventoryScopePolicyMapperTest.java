package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WmsInventoryScopePolicyMapperTest {
    @Test
    void readAndResetAreScopedAndResetRetainsMonotonicVersion() throws Exception {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:wms-scope-policy-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        var db = new JdbcTemplate(source);
        db.execute("CREATE SCHEMA mom_wms");
        db.execute("CREATE TABLE mom_wms.wms_inventory_scope_policy (tenant_id bigint, "
                + "factory_code varchar(100), warehouse_code varchar(100), rule_code varchar(32), "
                + "threshold_days integer, inherit_default boolean, version integer, "
                + "updated_at timestamp, updated_by varchar(100))");
        db.update("INSERT INTO mom_wms.wms_inventory_scope_policy VALUES "
                + "(11,'F1','W1','NEAR_EXPIRY',14,false,2,current_timestamp,'a'),"
                + "(11,'F1','W2','LONG_STAY',90,false,2,current_timestamp,'a'),"
                + "(11,'F2','W1','LONG_STAY',90,false,2,current_timestamp,'a'),"
                + "(12,'F1','W1','LONG_STAY',90,false,2,current_timestamp,'a')");
        var configuration = new MybatisConfiguration();
        configuration.addMapper(WmsInventoryScopePolicyMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(WmsInventoryScopePolicyMapper.class);

        var row = mapper.find(11L, "F1", "W1");
        assertEquals("NEAR_EXPIRY", row.ruleCode());
        assertEquals(14, row.thresholdDays());
        assertEquals(2, row.version());
        assertNull(mapper.find(10L, "F1", "W1"));
        assertEquals(0, mapper.resetVersion(11L, "F1", "W1", 1, "b"));
        assertEquals(1, mapper.resetVersion(11L, "F1", "W1", 2, "b"));
        assertEquals(0, mapper.resetVersion(11L, "F1", "W1", 2, "b"));
        var inherited = mapper.find(11L, "F1", "W1");
        assertEquals("ALL_POSITIVE", inherited.ruleCode());
        assertEquals(3, inherited.version());
        assertEquals(true, inherited.inheritDefault());
        assertEquals("LONG_STAY", mapper.find(11L, "F1", "W2").ruleCode());
        assertEquals("LONG_STAY", mapper.find(11L, "F2", "W1").ruleCode());
        assertEquals("LONG_STAY", mapper.find(12L, "F1", "W1").ruleCode());
    }
}
