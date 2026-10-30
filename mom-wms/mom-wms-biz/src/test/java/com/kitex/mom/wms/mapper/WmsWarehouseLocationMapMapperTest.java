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

class WmsWarehouseLocationMapMapperTest {
    @Test
    void findsAndDeletesOnlyExactOrganizationAndWarehouse() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:wms-location-map-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        var db = new JdbcTemplate(source);
        db.execute("CREATE SCHEMA mom_wms");
        db.execute("CREATE TABLE mom_wms.wms_warehouse_location_map (tenant_id bigint, "
                + "factory_code varchar(100), warehouse_code varchar(100), layout_json varchar(200), version integer)");
        db.update("INSERT INTO mom_wms.wms_warehouse_location_map VALUES "
                + "(11,'F1','W1','[]',3),(11,'F1','W2','[]',1),"
                + "(11,'F2','W1','[]',1),(12,'F1','W1','[]',1)");
        var configuration = new MybatisConfiguration();
        configuration.addMapper(WmsWarehouseLocationMapMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(WmsWarehouseLocationMapMapper.class);

        assertEquals(3, mapper.find(11L, "F1", "W1").version());
        assertEquals("[]", mapper.find(11L, "F1", "W1").layoutJson());
        assertNull(mapper.find(12L, "F2", "W1"));
        assertEquals(1, mapper.delete(11L, "F1", "W1"));
        assertEquals(3, db.queryForObject("SELECT count(*) FROM mom_wms.wms_warehouse_location_map", Integer.class));
    }
}
