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

class WmsInventoryCountTemplateMapperTest {
	@Test
	void templateReadsAndRunCompletionStayWithinTenantFactoryAndOriginalRequest() throws Exception {
		var source = new DriverManagerDataSource(
				"jdbc:h2:mem:wms-count-template-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		var db = new JdbcTemplate(source);
		db.execute("CREATE SCHEMA mom_wms");
		db.execute("CREATE TABLE mom_wms.wms_inventory_count_template (tenant_id bigint, factory_code varchar, "
				+ "template_code varchar, template_name varchar, warehouse_code varchar, rule_code varchar, "
				+ "threshold_days integer, site_codes_json varchar, stock_freeze_mode varchar, "
				+ "enabled boolean, version integer)");
		db.execute("CREATE TABLE mom_wms.wms_inventory_count_template_run (tenant_id bigint, factory_code varchar, "
				+ "request_id varchar, template_code varchar, template_version integer, "
				+ "template_snapshot_json varchar, triggered_by varchar, created_at timestamp, "
				+ "plan_id bigint, plan_no varchar)");
		db.update("INSERT INTO mom_wms.wms_inventory_count_template VALUES "
				+ "(11,'F1','CYCLE_A','临期盘点','W1','NEAR_EXPIRY',14,'[\"S1\"]','SNAPSHOT',true,3),"
				+ "(11,'F1','CYCLE_B','库龄盘点','W2','LONG_STAY',90,'[\"S2\"]','SNAPSHOT',true,1),"
				+ "(11,'F2','CYCLE_A','其他工厂','W1','ALL_POSITIVE',null,'[\"S1\"]','SNAPSHOT',true,1)");
		db.update("INSERT INTO mom_wms.wms_inventory_count_template_run VALUES "
				+ "(11,'F1','req-1','CYCLE_A',3,'{\"templateCode\":\"CYCLE_A\"}','operator',current_timestamp,null,null),"
				+ "(11,'F2','req-1','CYCLE_A',1,'{}','other',current_timestamp,null,null)");
		var configuration = new MybatisConfiguration();
		configuration.addMapper(WmsInventoryCountTemplateMapper.class);
		var factory = new MybatisSqlSessionFactoryBean();
		factory.setDataSource(source);
		factory.setConfiguration(configuration);
		var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(WmsInventoryCountTemplateMapper.class);

		var template = mapper.find(11L, "F1", "CYCLE_A");
		assertEquals("W1", template.warehouseCode());
		assertEquals(14, template.thresholdDays());
		assertEquals(3, template.version());
		assertEquals(1, mapper.list(11L, "F1", "W1").size());
		assertEquals(1L, mapper.countInWarehouse(11L, "F1", "W1"));
		assertNull(mapper.find(12L, "F1", "CYCLE_A"));
		assertEquals(1, mapper.completeRun(11L, "F1", "req-1", 901L, "PD-1"));
		assertEquals(0, mapper.completeRun(11L, "F1", "req-1", 902L, "PD-2"));
		assertEquals(901L, mapper.findRunForUpdate(11L, "F1", "req-1").planId());
		assertNull(mapper.findRunForUpdate(11L, "F2", "req-1").planId());
		assertEquals("operator", mapper.recentRuns(11L, "F1", "W1", "CYCLE_A").get(0).triggeredBy());
		assertEquals("{\"templateCode\":\"CYCLE_A\"}",
				mapper.recentRuns(11L, "F1", "W1", "CYCLE_A").get(0).templateSnapshotJson());
		assertEquals(0, mapper.recentRuns(11L, "F1", "W2", "CYCLE_A").size());
	}
}
