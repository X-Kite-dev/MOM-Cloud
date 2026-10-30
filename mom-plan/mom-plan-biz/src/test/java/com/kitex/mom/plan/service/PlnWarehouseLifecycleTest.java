package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.plan.mapper.PlnWarehouseLifecycleMapper;
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

/** 实际依赖SQL在H2执行；固定用例覆盖计划尚未生成库存/单据时的仓库历史引用与范围隔离。 */
class PlnWarehouseLifecycleTest {

	private static final List<String> REFERENCES = List.of(
			"pln_product_demand,demand_warehouse_code",
			"pln_mps_line,warehouse_code",
			"plan_mat_mrp,demand_warehouse_code",
			"pln_scheduling_batch_allocation,demand_warehouse_code",
			"pln_product_demand_change,target_warehouse_code",
			"pln_quality_supplement_demand,demand_warehouse_code",
			"plan_mrp_inventory_pegging,warehouse_code");

	private JdbcTemplate db;
	private PlnWarehouseLifecycleService service;

	@BeforeEach
	void setup() throws Exception {
		FactoryContextHolder.setFactoryCode("F1");
		TenantContextHolder.setTenantId(1L);
		var source = new DriverManagerDataSource("jdbc:h2:mem:plan-warehouse-references-" + UUID.randomUUID()
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
		configuration.addMapper(PlnWarehouseLifecycleMapper.class);
		var factory = new MybatisSqlSessionFactoryBean();
		factory.setDataSource(source);
		factory.setConfiguration(configuration);
		var sessions = new SqlSessionTemplate(factory.getObject());
		service = new PlnWarehouseLifecycleService(sessions.getMapper(PlnWarehouseLifecycleMapper.class));
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

		assertTrue(service.references("W1").isEmpty());

		db.update(sql, 1, "F1", "W1");

		assertFalse(service.references("W1").isEmpty(), fixture);
	}

	@Test
	void missingOrSkippedContextCannotProduceAnUnscopedAnswer() {
		FactoryContextHolder.clear();

		assertThrows(CheckedException.class, () -> service.references("W1"));

		FactoryContextHolder.setFactoryCode("F1");
		FactoryContextHolder.setFactorySkip();

		assertThrows(CheckedException.class, () -> service.references("W1"));

		FactoryContextHolder.clear();
		FactoryContextHolder.setFactoryCode("F1");
		TenantContextHolder.clear();

		assertThrows(CheckedException.class, () -> service.references("W1"));
	}

	@Test
	void databaseFailureCannotReturnNoReferences() {
		db.execute("drop table pln_product_demand");

		assertThrows(RuntimeException.class, () -> service.references("W1"));
	}
}
