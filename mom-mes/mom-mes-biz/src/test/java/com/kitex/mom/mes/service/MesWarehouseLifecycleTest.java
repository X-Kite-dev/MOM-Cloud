package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.mapper.MesWarehouseLifecycleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 实际依赖SQL在H2执行；固定用例覆盖各类历史、双向调拨与租户/工厂隔离。 */
class MesWarehouseLifecycleTest {

	private static final List<String> REFERENCES = List.of(
			"mes_material_list_detail,default_warehouse",
			"mes_material_list_record,warehouse_code",
			"mes_task_in_wms_receipt,warehouse_code",
			"mes_warehouse_feed,warehouse_code",
			"mes_warehouse_inout,warehouse_code",
			"mes_warehouse_inventory,warehouse_code",
			"mes_warehouse_inventory_record,warehouse_code",
			"mes_warehouse_live_stock,warehouse_code",
			"mes_warehouse_lock_detail,warehouse_code",
			"mes_warehouse_material_in_bill,warehouse_code",
			"mes_warehouse_picking_bill,warehouse_code",
			"mes_warehouse_picking_bill_detail,warehouse_code",
			"mes_warehouse_stock_feed_judge,warehouse_code",
			"mes_warehouse_unqualified_lock,warehouse_code");

	private JdbcTemplate db;
	private MesWarehouseLifecycleService service;

	@BeforeEach
	void setup() throws Exception {
		FactoryContextHolder.setFactoryCode("F1");
		TenantContextHolder.setTenantId(1L);
		var source = new DriverManagerDataSource("jdbc:h2:mem:mes-warehouse-references-" + UUID.randomUUID()
				+ ";DB_CLOSE_DELAY=-1", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(source);
		Map<String, Set<String>> columns = new LinkedHashMap<>();
		for (String fixture : REFERENCES) {
			String[] fields = fixture.split(",");
			columns.computeIfAbsent(fields[0], ignored -> new LinkedHashSet<>()).add(fields[1]);
		}
		for (var entry : columns.entrySet()) {
			String sql = "create table " + entry.getKey()
					+ "(tenant_id bigint,factory_code varchar,del_flag varchar";
			for (String column : entry.getValue()) {
				sql += "," + column + " varchar";
			}
			db.execute(sql + ")");
		}
		var configuration = new MybatisConfiguration();
		configuration.addMapper(MesWarehouseLifecycleMapper.class);
		var factory = new MybatisSqlSessionFactoryBean();
		factory.setDataSource(source);
		factory.setConfiguration(configuration);
		var sessions = new SqlSessionTemplate(factory.getObject());
		service = new MesWarehouseLifecycleService(sessions.getMapper(MesWarehouseLifecycleMapper.class));
	}

	@AfterEach
	void clearContext() {
		FactoryContextHolder.clear();
		TenantContextHolder.clear();
	}

	static Stream<String> referenceExamples() {
		return REFERENCES.stream();
	}

	@ParameterizedTest
	@MethodSource("referenceExamples")
	void eachHistoricalBusinessReferenceIsScopedAndPreventsDeletion(String fixture) {
		String[] fields = fixture.split(",");
		String sql = "insert into " + fields[0] + "(tenant_id,factory_code," + fields[1]
				+ ",del_flag) values(?,?,?,'1')";
		db.update(sql, 2, "F1", "W1");
		db.update(sql, 1, "F2", "W1");

		assertTrue(service.references("W1", 10L).isEmpty());

		db.update(sql, 1, "F1", "W1");

		assertFalse(service.references("W1", 10L).isEmpty(), fixture);
	}

	@Test
	void defaultWarehouseIdAndLegacyCodeBothPreventDeletion() {
		for (String warehouse : List.of("10", "W1")) {
			db.update("delete from mes_material_list_detail");
			db.update("insert into mes_material_list_detail(tenant_id,factory_code,default_warehouse,del_flag)"
					+ " values(1,'F1',?,'1')", warehouse);

			assertFalse(service.references("W1", 10L).isEmpty());
		}
	}

	@Test
	void missingOrSkippedContextCannotProduceAnUnscopedAnswer() {
		FactoryContextHolder.clear();

		assertThrows(CheckedException.class, () -> service.references("W1", 10L));

		FactoryContextHolder.setFactoryCode("F1");
		FactoryContextHolder.setFactorySkip();

		assertThrows(CheckedException.class, () -> service.references("W1", 10L));

		FactoryContextHolder.clear();
		FactoryContextHolder.setFactoryCode("F1");
		TenantContextHolder.clear();

		assertThrows(CheckedException.class, () -> service.references("W1", 10L));
	}

	@Test
	void databaseFailureCannotReturnNoReferences() {
		db.execute("drop table mes_material_list_detail");

		assertThrows(RuntimeException.class, () -> service.references("W1", 10L));
	}
}
