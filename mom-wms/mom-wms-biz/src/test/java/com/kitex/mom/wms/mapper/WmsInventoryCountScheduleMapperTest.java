package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WmsInventoryCountScheduleMapperTest {
	@Test
	void dueQueryKeepsTenantFactoryAndTemplateWarehouseBoundaries() throws Exception {
		var source = new DriverManagerDataSource(
				"jdbc:h2:mem:wms-count-schedule-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		var db = new JdbcTemplate(source);
		db.execute("CREATE SCHEMA mom_wms");
		db.execute("CREATE TABLE mom_wms.wms_inventory_count_template (tenant_id bigint, "
				+ "factory_code varchar, template_code varchar, warehouse_code varchar)");
		db.execute("CREATE TABLE mom_wms.wms_inventory_count_schedule (tenant_id bigint, "
				+ "factory_code varchar, template_code varchar, enabled boolean, interval_days integer, "
				+ "time_of_day varchar, zone_id varchar, next_run_utc timestamp, version integer)");
		db.update("INSERT INTO mom_wms.wms_inventory_count_template VALUES "
				+ "(11,'F1','CYCLE_A','W1'),(11,'F1','CYCLE_B','W2'),"
				+ "(11,'F2','CYCLE_A','W1'),(12,'F1','CYCLE_A','W1')");
		db.update("INSERT INTO mom_wms.wms_inventory_count_schedule VALUES "
				+ "(11,'F1','CYCLE_A',true,1,'10:00','Asia/Shanghai',TIMESTAMP '2026-10-27 02:00:00',3),"
				+ "(11,'F1','CYCLE_B',false,1,'10:00','Asia/Shanghai',null,1),"
				+ "(11,'F2','CYCLE_A',true,1,'10:00','Asia/Shanghai',TIMESTAMP '2026-10-27 02:00:00',1),"
				+ "(12,'F1','CYCLE_A',true,1,'10:00','Asia/Shanghai',TIMESTAMP '2026-10-27 02:00:00',1)");
		var configuration = new MybatisConfiguration();
		configuration.addMapper(WmsInventoryCountScheduleMapper.class);
		var factory = new MybatisSqlSessionFactoryBean();
		factory.setDataSource(source);
		factory.setConfiguration(configuration);
		var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(WmsInventoryCountScheduleMapper.class);

		var due = mapper.due(11L, "F1", LocalDateTime.parse("2026-10-27T02:00:00"), 10);

		assertEquals(1, due.size());
		assertEquals("CYCLE_A", due.get(0).templateCode());
		assertEquals("W1", due.get(0).warehouseCode());
		assertEquals(3, due.get(0).version());
		assertEquals(3, mapper.find(11L, "F1", "CYCLE_A", "W1").version());
		assertNull(mapper.find(11L, "F1", "CYCLE_A", "W2"));
		assertEquals(0, mapper.due(11L, "F1", LocalDateTime.parse("2026-10-27T01:59:59"), 10).size());
	}
}
