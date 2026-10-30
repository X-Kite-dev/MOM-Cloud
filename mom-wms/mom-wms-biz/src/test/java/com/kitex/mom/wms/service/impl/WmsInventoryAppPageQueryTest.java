package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.dto.WmsInsideInventoryPlanQueryDTO;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanEntity;
import com.kitex.mom.wms.mapper.WmsInsideInventoryPlanMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Execute the production mapper XML and actual MP pagination against H2; PG/interceptor integration remains separate.
 */
class WmsInventoryAppPageQueryTest {
	private SqlSession session;
	private WmsInsideInventoryPlanMapper mapper;
	private JdbcTemplate jdbc;

	@BeforeEach
	void setup() throws Exception {
		var dataSource = new JdbcDataSource();
		dataSource.setURL("jdbc:h2:mem:inventory-list-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
		jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("create table wms_inside_inventory_plan (id bigint primary key, "
				+ "tenant_id bigint, factory_code varchar(30), "
				+ "plan_no varchar(40), warehouse_code varchar(30), warehouse_name varchar(80), status varchar(1), handle_state varchar(1), "
				+ "stock_freeze_mode varchar(16) not null default 'SNAPSHOT', "
				+ "cancel_reason varchar(500), cancel_request_id varchar(64), cancel_by varchar(64), cancel_time timestamp, "
				+ "create_time timestamp, update_time timestamp, create_by varchar(30), update_by varchar(30), del_flag varchar(1))");
		jdbc.execute("create table wms_inside_inventory_plan_detail (id bigint primary "
				+ "key, plan_id bigint, tenant_id bigint, "
				+ "factory_code varchar(30), warehouse_code varchar(30), site_code varchar(30), "
				+ "mat_batch varchar(40), mat_code varchar(40), mat_name varchar(80), del_flag varchar(1))");
		plan(jdbc, 1, 1, "F1", "W1", "0", "0", "0");
		plan(jdbc, 2, 1, "F1", "W1", "1", "0", "0");
		plan(jdbc, 3, 1, "F1", "W1", "1", "1", "0");
		plan(jdbc, 4, 1, "F1", "W2", "0", "0", "0");
		plan(jdbc, 5, 1, "F2", "W1", "0", "0", "0");
		plan(jdbc, 6, 2, "F1", "W1", "0", "0", "0");
		plan(jdbc, 7, 1, "F1", "W1", "0", "0", "1");
		plan(jdbc, 8, 1, "F1", "W1", "0", "0", "0"); // Empty tasks still belong in an unfiltered list.
		detail(jdbc, 11, 1, 1, "F1", "OTHER", "Other", "0");
		detail(jdbc, 12, 1, 2, "F1", "MATCH", "Target", "0"); // Foreign tenant cannot make task 1 match.
		detail(jdbc, 13, 1, 1, "F2", "MATCH", "Target", "0");
		detail(jdbc, 21, 2, 1, "F1", "MATCH", "Target", "0");
		detail(jdbc, 22, 2, 1, "F1", "MATCH", "Target", "0"); // Multiple matching details count as one task.
		detail(jdbc, 31, 3, 1, "F1", "MATCH", "Target", "1");
		var configuration = new MybatisConfiguration();
		configuration.setMapUnderscoreToCamelCase(true);
		configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
		var pagination = new MybatisPlusInterceptor();
		pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
		configuration.addInterceptor(pagination);
		String resource = "mapper/WmsInsideInventoryPlanMapper.xml";
		try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
			assertNotNull(input, "production mapper XML must be on the test classpath");
			new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
		}
		session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession();
		mapper = session.getMapper(WmsInsideInventoryPlanMapper.class);
	}

	@AfterEach
	void close() {
		if (session != null) {
			session.close();
		}
		FactoryContextHolder.clear();
		TenantContextHolder.clear();
	}

	@Test
	void taskPagesHaveStableIdentityOrderAndNoDetailMultiplication() {
		var first = mapper.appPage(new Page<>(1, 2), query());

		assertEquals(4, first.getTotal());
		assertEquals(List.of(8L, 3L), ids(first));
		assertEquals(List.of(2L, 1L), ids(mapper.appPage(new Page<>(2, 2), query())));
	}

	@Test
	void eachListRecordCarriesItsRealTenantAndFactory() {
		var page = mapper.appPage(new Page<>(1, 10), query());

		assertFalse(page.getRecords().isEmpty());
		for (var row : page.getRecords()) {
			assertEquals(1L, row.getTenantId());
			assertEquals("F1", row.getFactoryCode());
			assertEquals("W1", row.getWarehouseCode());
		}
	}

	@Test
	void materialSearchUsesOnlyActiveDetailsInTheSameOrganization() {
		var query = query();
		query.setMatCode("MAT");
		query.setMatName("arg");
		var page = mapper.appPage(new Page<>(1, 10), query);

		assertEquals(1, page.getTotal());
		assertEquals(List.of(2L), ids(page));
	}

	@Test
	void unfinishedCountLookupIncludesOnlyTheRequestedTenantFactoryAndWarehouse() {
		jdbc.update("update wms_inside_inventory_plan_detail set warehouse_code='W1', site_code='S1', "
				+ "mat_batch='B1' where id in (11, 12, 13, 21, 31)");
		jdbc.update("update wms_inside_inventory_plan_detail set warehouse_code='W2', site_code='S1', "
				+ "mat_batch='B1' where id=22");
		detail(jdbc, 41, 4, 1, "F1", "MATCH", "Target", "0");
		jdbc.update("update wms_inside_inventory_plan_detail set warehouse_code='W2', site_code='S1' where id=41");

		var rows = mapper.findUnfinishedDetails(1L, "F1", "W1");

		assertEquals(List.of(11L, 21L), rows.stream().map(row -> row.getId()).sorted().toList());
		assertTrue(rows.stream().allMatch(row -> row.getPlanId() != null
				&& "W1".equals(row.getWarehouseCode()) && "S1".equals(row.getSiteCode())));
	}

	@Test
	void planNumberAndWarehouseSearchRemainSupported() {
		var query = query();
		query.setPlanNo("PD-2");

		assertEquals(List.of(2L), ids(mapper.appPage(new Page<>(1, 10), query)));

		query.setWarehouseCode("W2");

		assertEquals(0, mapper.appPage(new Page<>(1, 10), query).getTotal());
	}

	@Test
	void recordingAndAdjustmentStatesFilterSeparately() {
		var query = query();
		query.setStatus("1");
		query.setHandleState("0");

		assertEquals(List.of(2L), ids(mapper.appPage(new Page<>(1, 10), query)));

		query.setHandleState("1");

		assertEquals(List.of(3L), ids(mapper.appPage(new Page<>(1, 10), query)));
	}

	@Test
	void cancelledTaskCarriesAuditAndCanBeFilteredForReadOnlyMobileView() {
		jdbc.update("update wms_inside_inventory_plan set status='2', handle_state='2', "
				+ "cancel_reason='库存已变动', cancel_by='counter', cancel_time=timestamp '2026-10-24 10:00:00' where id=2");
		var query = query();
		query.setHandleState("2");

		var result = mapper.appPage(new Page<>(1, 10), query);

		assertEquals(List.of(2L), ids(result));
		assertEquals("2", result.getRecords().get(0).getStatus());
		assertEquals("库存已变动", result.getRecords().get(0).getCancelReason());
		assertEquals("counter", result.getRecords().get(0).getCancelBy());
		assertNotNull(result.getRecords().get(0).getCancelTime());
	}

	@Test
	void applicationServiceReplacesUntrustedTenantAndFactoryQueryValues() {
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
		var service = new WmsInsideInventoryPlanServiceImpl(null, null, null, null, null, null, null, null);
		ReflectionTestUtils.setField(service, "baseMapper", mapper);
		var query = query();
		query.setTenantId(2L);
		query.setFactoryCode("F2");

		var result = service.appPage(new Page<>(1, 10), query);

		assertEquals(1L, query.getTenantId());
		assertEquals("F1", query.getFactoryCode());
		assertEquals(4, result.getTotal());
		assertTrue(result.getRecords().stream().allMatch(row -> "F1".equals(row.getFactoryCode())));
	}

	@Test
	void exportUsesTheVisibleTaskFiltersAndSelectedIds() {
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
		var service = new WmsInsideInventoryPlanServiceImpl(null, null, null, null, null, null, null, null);
		ReflectionTestUtils.setField(service, "baseMapper", mapper);
		var filter = new WmsInsideInventoryPlanEntity();
		filter.setWarehouseCode("W1");
		filter.setPlanNo("D-");
		filter.setStatus("1");
		filter.setHandleState("0");

		var exported = service.export(filter, new Long[] { 2L, 3L, 4L, 5L, 6L });

		assertEquals(List.of("PD-2"), exported.stream().map(row -> row.getPlanNo()).toList());
	}

	private static List<Long> ids(Page<WmsInsideInventoryPlanEntity> page) {
		return page.getRecords().stream().map(WmsInsideInventoryPlanEntity::getId).toList();
	}

	private static WmsInsideInventoryPlanQueryDTO query() {
		var query = new WmsInsideInventoryPlanQueryDTO();
		query.setTenantId(1L);
		query.setFactoryCode("F1");
		query.setWarehouseCode("W1");
		return query;
	}

	private static void plan(JdbcTemplate jdbc, long id, long tenant, String factory, String warehouse,
			String status, String handled, String deleted) {
		jdbc.update("insert into wms_inside_inventory_plan "
				+ "(id,tenant_id,factory_code,plan_no,warehouse_code,warehouse_name,status,handle_state,"
				+ "create_time,update_time,create_by,update_by,del_flag) values (?,?,?,?,?,?,?,?,"
				+ "timestamp '2026-10-23 10:00:00',"
				+ "timestamp '2026-10-23 10:00:00','operator','operator',?)",
				id, tenant, factory, "PD-" + id, warehouse, "Warehouse " + warehouse, status, handled, deleted);
	}

	private static void detail(JdbcTemplate jdbc, long id, long plan, long tenant, String factory,
			String material, String name, String deleted) {
		jdbc.update("insert into wms_inside_inventory_plan_detail "
				+ "(id,plan_id,tenant_id,factory_code,mat_code,mat_name,del_flag) values (?,?,?,?,?,?,?)",
				id, plan, tenant, factory,
				material, name, deleted);
	}
}
