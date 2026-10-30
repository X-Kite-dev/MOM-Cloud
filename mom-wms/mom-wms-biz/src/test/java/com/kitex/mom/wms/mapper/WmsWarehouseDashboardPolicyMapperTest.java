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

class WmsWarehouseDashboardPolicyMapperTest {
    @Test
    void savedValuesMapFromSqlAndCannotCrossTenantFactoryOrWarehouse() throws Exception {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:wms-dashboard-policy-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        var db = new JdbcTemplate(source);
        db.execute("CREATE SCHEMA mom_wms");
        db.execute("CREATE TABLE mom_wms.wms_warehouse_dashboard_policy (tenant_id bigint, "
                + "factory_code varchar(100), warehouse_code varchar(100), near_expiry_days integer, "
                + "slow_moving_days integer, version integer)");
        db.update("INSERT INTO mom_wms.wms_warehouse_dashboard_policy VALUES (11,'F1','W1',45,120,3)");

        var configuration = new MybatisConfiguration();
        configuration.addMapper(WmsWarehouseDashboardPolicyMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(WmsWarehouseDashboardPolicyMapper.class);

        var actual = mapper.find(11L, "F1", "W1");
        assertEquals(45, actual.nearExpiryDays());
        assertEquals(120, actual.slowMovingDays());
        assertEquals(3, actual.version());
        assertNull(mapper.find(12L, "F1", "W1"));
        assertNull(mapper.find(11L, "F2", "W1"));
        assertNull(mapper.find(11L, "F1", "W2"));
    }

    @Test
    void resetDeleteMatchesScopeAndCurrentVersion() throws Exception {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:wms-dashboard-reset-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        var db = new JdbcTemplate(source);
        db.execute("CREATE SCHEMA mom_wms");
        db.execute("CREATE TABLE mom_wms.wms_warehouse_dashboard_policy (tenant_id bigint, "
                + "factory_code varchar(100), warehouse_code varchar(100), near_expiry_days integer, "
                + "slow_moving_days integer, version integer)");
        db.update("INSERT INTO mom_wms.wms_warehouse_dashboard_policy VALUES "
                + "(11,'F1','W1',45,120,3),(11,'F1','W2',45,120,3),"
                + "(11,'F2','W1',45,120,3),(12,'F1','W1',45,120,3)");
        var configuration = new MybatisConfiguration();
        configuration.addMapper(WmsWarehouseDashboardPolicyMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(WmsWarehouseDashboardPolicyMapper.class);

        assertEquals(0, mapper.deleteVersion(11L, "F1", "W1", 2));
        assertEquals(1, mapper.deleteVersion(11L, "F1", "W1", 3));
        assertEquals(3, db.queryForObject("SELECT count(*) FROM mom_wms.wms_warehouse_dashboard_policy", Integer.class));
    }
}
