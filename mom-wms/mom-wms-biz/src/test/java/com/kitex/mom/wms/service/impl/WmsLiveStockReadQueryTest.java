package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.admin.api.feign.RemoteDictService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.dto.WmsLiveStockDynamicGroupDTO;
import com.kitex.mom.wms.dto.WmsLiveStockQueryDTO;
import com.kitex.mom.wms.dto.WmsLiveStockQuerySumDTO;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.mapper.WmsOutNoticeDetailMapper;
import com.kitex.mom.wms.service.WmsMaterialReservationService;
import com.kitex.mom.wms.service.WmsSalesStockReservationService;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Actual production filter/order wrapper and MP pagination; this does not replace PG/security integration. */
class WmsLiveStockReadQueryTest {
	private SqlSession session;
	private WmsLiveStockMapper mapper;
	private WmsOutNoticeDetailMapper noticeMapper;
	private JdbcTemplate jdbc;
	private final WmsLiveStockServiceImpl service = new WmsLiveStockServiceImpl(null, null, null, null, null, null,
			null, null, null, null);

	@BeforeEach
	void setup() {
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
		var dataSource = new JdbcDataSource();
		dataSource.setURL("jdbc:h2:mem:stock-read-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
		jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("create table wms_live_stock (id bigint primary key, tenant_id "
				+ "bigint, factory_code varchar(30), "
				+ "warehouse_code varchar(30), warehouse_name varchar(80), mat_code varchar(40), mat_name varchar(80), mat_batch varchar(40), "
				+ "site_code varchar(30),site_name varchar(80),container_code varchar(30),mat_spec varchar(80),"
				+ "mat_unit varchar(20),supplier_code varchar(30),supplier_name varchar(80),mat_num double precision, "
				+ "create_time timestamp, del_flag varchar(1),mat_barcode varchar(80),serial_managed boolean,"
				+ "stock_owner_type varchar(32),stock_owner_code varchar(64),source_business_no varchar(128),"
				+ "status varchar(1),is_quality_test varchar(1),quality_result varchar(32),in_time timestamp,"
				+ "quality_status varchar(32),mat_valid_end_date date)");
		jdbc.execute("create table wms_out_notice_detail (id bigint primary key,tenant_id bigint,"
				+ "factory_code varchar(30),notice_id bigint,warehouse_code varchar(30),"
				+ "mat_code varchar(40),del_flag varchar(1))");
		jdbc.execute("create table wms_out_notice (id bigint primary key,tenant_id bigint,"
				+ "factory_code varchar(30),warehouse_code varchar(30),state varchar(1),del_flag varchar(1))");
		for (long id = 1; id <= 5; id++) {
			jdbc.update("insert into wms_live_stock(id,tenant_id,factory_code,warehouse_code,mat_code,mat_name,"
					+ "mat_batch,site_code,supplier_code,supplier_name,mat_num,create_time,del_flag) "
					+ "values (?,1,'F1',?,'M1','Material',?,?,'S1','Supplier',?,"
					+ "timestamp '2026-10-23 10:00:00','0')",
					id, id >= 4 ? "W2" : "W1", id == 2 ? "B2" : "B1", id == 2 ? "L2" : "L1", id == 3 ? 0d : 1d);
		}
		jdbc.update("update wms_live_stock set mat_barcode='SERIAL-1',stock_owner_type='CUSTOMER_SUPPLIED',"
				+ "stock_owner_code='CUST-1',source_business_no='CUSTOMER-ORDER-1' where id=1");
		jdbc.update("update wms_live_stock set status='0',is_quality_test='0'");
		jdbc.update("update wms_live_stock set is_quality_test='1',quality_result='QUALIFIED',"
				+ "quality_status='HOLD' where id=2");
		jdbc.update("update wms_live_stock set status='1' where id=4");
		jdbc.update("update wms_live_stock set is_quality_test='1',quality_result='CONCESSION',"
				+ "quality_status='CONCESSION',stock_owner_type='CONSIGNED',stock_owner_code='SUPPLIER-1' where id=5");
		var configuration = new MybatisConfiguration();
		configuration.setMapUnderscoreToCamelCase(true);
		configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
		var pagination = new MybatisPlusInterceptor();
		pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
		configuration.addInterceptor(pagination);
		configuration.addMapper(WmsLiveStockMapper.class);
		configuration.addMapper(WmsOutNoticeDetailMapper.class);
		session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession();
		mapper = session.getMapper(WmsLiveStockMapper.class);
		noticeMapper = session.getMapper(WmsOutNoticeDetailMapper.class);
		var productionReservations = mock(WmsMaterialReservationService.class);
		var salesReservations = mock(WmsSalesStockReservationService.class);
		when(productionReservations.activeReservedNums(anySet())).thenReturn(Map.of());
		when(salesReservations.activeReservedNums(anySet())).thenReturn(Map.of());
		ReflectionTestUtils.setField(service, "wmsMaterialReservationService", productionReservations);
		ReflectionTestUtils.setField(service, "wmsSalesStockReservationService", salesReservations);
	}

	@AfterEach
	void close() {
		if (session != null) {
			session.close();
		}
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	@Test
	void sameTimestampPagesHaveStableDescendingIdentityOrder() {
		var query = new WmsLiveStockQueryDTO().setWarehouseCode("W1");
		var first = page(query, 1, 1);

		assertEquals(2, first.getTotal());
		assertEquals(List.of(2L), ids(first));
		assertEquals(List.of(1L), ids(page(query, 2, 1)));
	}

	@Test
	void batchSupplierAndExactLocationUseExistingFilterSemantics() {
		var query = new WmsLiveStockQueryDTO().setWarehouseCode("W1").setSupplierCode("S1").setMatBatch("B2");

		assertEquals(List.of(2L), ids(page(query, 1, 10)));

		query.setSiteCode(List.of("L1"));

		assertEquals(0, page(query, 1, 10).getTotal());

		query.setSiteCode(List.of("L2"));

		assertEquals(List.of(2L), ids(page(query, 1, 10)));
	}

	@Test
	void exactStockDrilldownKeepsWarehouseAndNonzeroInventoryRules() {
		var query = new WmsLiveStockQueryDTO().setWarehouseCode("W1").setStockId(1L);

		assertEquals(List.of(1L), ids(page(query, 1, 10)));

		query.setStockId(4L);

		assertEquals(0, page(query, 1, 10).getTotal());

		query.setStockId(3L);

		assertEquals(0, page(query, 1, 10).getTotal());
	}

	@Test
	void ownerSourceAndBarcodeCanBeFilteredTogether() {
		var query = new WmsLiveStockQueryDTO().setWarehouseCode("W1").setMatBarcode("SERIAL-1")
				.setStockOwnerType("CUSTOMER_SUPPLIED").setStockOwnerCode("CUST-1")
				.setSourceBusinessNo("CUSTOMER-ORDER-1");

		assertEquals(List.of(1L), ids(page(query, 1, 10)));
		query.setStockOwnerCode("CUST-2");
		assertEquals(0, page(query, 1, 10).getTotal());
	}

	@Test
	void selectedExportIdsStillRespectWarehouseAndOtherFilters() {
		var dicts = mock(RemoteDictService.class);
		when(dicts.getDictByType("material_unit")).thenReturn(R.ok(List.of()));
		ReflectionTestUtils.setField(service, "remoteDictService", dicts);
		var exportService = spy(service);
		var observed = new AtomicReference<Wrapper<WmsLiveStockEntity>>();
		doAnswer(call -> {
			observed.set(call.getArgument(0));
			return List.of();
		}).when(exportService).list(any(Wrapper.class));

		exportService.listDetail(new WmsLiveStockQueryDTO().setWarehouseCode("W1")
				.setMatCode("M1"), new String[] { "1", "4" });

		assertNotNull(observed.get());
		assertTrue(observed.get().getSqlSegment().contains("warehouse_code"));
		assertTrue(observed.get().getSqlSegment().contains("mat_code"));
		assertTrue(observed.get().getSqlSegment().contains("id"));
		assertTrue(((AbstractWrapper<?, ?, ?>) observed.get()).getParamNameValuePairs().containsValue("W1"));
		@SuppressWarnings("unchecked")
		var selected = (LambdaQueryWrapper<WmsLiveStockEntity>) observed.get();
		assertEquals(List.of(1L), mapper.selectList(selected.select(WmsLiveStockEntity::getId))
				.stream().map(WmsLiveStockEntity::getId).toList());
	}

	@Test
	void dynamicInventoryGroupRejectsUnlistedSqlFields() {
		var query = new WmsLiveStockDynamicGroupDTO();
		query.setGroupFields(List.of("mat_code", "mat_num) from wms_live_stock --"));

		assertThrows(CheckedException.class,
				() -> service.queryByDynamicGroup(new Page<>(1, 10), query));
	}

	@Test
	void exactOutboundSelectionKeepsStockIdentityAndExcludesUnusableRows() {
		var query = new WmsLiveStockQueryDTO().setWarehouseCode("W1");
		var wrapper = service.exactOutboundQuery(query).select(WmsLiveStockEntity::getId,
				WmsLiveStockEntity::getStockOwnerType, WmsLiveStockEntity::getStockOwnerCode);
		var result = mapper.selectPage(new Page<>(1, 10), wrapper);

		assertEquals(List.of(1L), ids(result));
		assertEquals("CUSTOMER_SUPPLIED", result.getRecords().get(0).getStockOwnerType());
		assertEquals("CUST-1", result.getRecords().get(0).getStockOwnerCode());

		var concession = mapper.selectPage(new Page<>(1, 10), service.exactOutboundQuery(
				new WmsLiveStockQueryDTO().setWarehouseCode("W2")).select(WmsLiveStockEntity::getId));
		assertEquals(List.of(5L), ids(concession));
	}

	@Test
	void exactOutboundSelectionRequiresWarehouse() {
		var query = new WmsLiveStockQueryDTO().setExactStock(true);

		assertThrows(CheckedException.class, () -> service.pageWhenOut(new Page<>(1, 10), query));
	}

	@Test
	void groupedOutboundSelectionExcludesExternalAndHeldStock() {
		ReflectionTestUtils.setField(service, "baseMapper", mapper);
		var query = new WmsLiveStockQueryDTO().setWarehouseCode("W1");

		assertEquals(0, service.pageWhenOut(new Page<>(1, 10), query).getTotal());
		jdbc.update("update wms_live_stock set is_quality_test='0',quality_result=null,quality_status=null where id=2");
		session.clearCache();
		assertEquals(1, service.pageWhenOut(new Page<>(1, 10), query).getTotal());
		jdbc.update("update wms_live_stock set mat_num=-1 where id=3");
		session.clearCache();
		assertEquals(1, service.pageWhenOut(new Page<>(1, 10), query).getTotal());
	}

	@Test
	void groupedOutboundSelectionRequiresWarehouseAndExcludesOtherOrganizations() {
		ReflectionTestUtils.setField(service, "baseMapper", mapper);
		assertThrows(CheckedException.class,
				() -> service.pageWhenOut(new Page<>(1, 10), new WmsLiveStockQueryDTO()));

		jdbc.update("update wms_live_stock set is_quality_test='0',quality_result=null,quality_status=null where id=2");
		jdbc.update("insert into wms_live_stock(id,tenant_id,factory_code,warehouse_code,mat_code,mat_name,"
				+ "mat_batch,site_code,mat_num,status,is_quality_test,del_flag) values "
				+ "(6,2,'F1','W1','M1','Foreign tenant','B2','L2',50,'0','0','0'),"
				+ "(7,1,'F2','W1','M1','Foreign factory','B2','L2',70,'0','0','0')");
		session.clearCache();

		var result = service.pageWhenOut(new Page<>(1, 10), new WmsLiveStockQueryDTO().setWarehouseCode("W1"));
		assertEquals(1, result.getTotal());
		assertEquals(1d, result.getRecords().get(0).getMatNum());
	}

	@Test
	void mobileStockSumIsLimitedToCurrentTenantAndFactory() {
		ReflectionTestUtils.setField(service, "baseMapper", mapper);
		jdbc.update("insert into wms_live_stock(id,tenant_id,factory_code,warehouse_code,mat_code,mat_name,"
				+ "mat_batch,site_code,mat_num,in_time,del_flag) values "
				+ "(6,2,'F1','W1','M1','Foreign tenant','B1','L1',50,timestamp '2026-10-24 10:00:00','0'),"
				+ "(7,1,'F2','W1','M1','Foreign factory','B1','L1',70,timestamp '2026-10-24 11:00:00','0')");
		session.clearCache();
		var query = new WmsLiveStockQuerySumDTO();
		query.setWarehouseCode("W1");
		query.setSiteCode("L1");
		query.setMatCode("M1");

		var result = service.getSumByParams(query);
		assertEquals(1d, result.getMatNum());
		assertEquals("Material", result.getMatName());
	}

	@Test
	void mobileStockSumRejectsMixedPhysicalBusinessIdentity() {
		ReflectionTestUtils.setField(service, "baseMapper", mapper);
		var query = new WmsLiveStockQuerySumDTO();
		query.setWarehouseCode("W1");
		query.setSiteCode("L1");
		query.setMatCode("M1");
		jdbc.update("insert into wms_live_stock(id,tenant_id,factory_code,warehouse_code,mat_code,"
				+ "mat_batch,site_code,mat_num,stock_owner_type,mat_unit,status,del_flag)"
				+ " values(8,1,'F1','W1','M1','B1','L1',2,'OWNED','PCS','0','0')");
		session.clearCache();

		assertThrows(CheckedException.class, () -> service.getSumByParams(query));

		jdbc.update("update wms_live_stock set stock_owner_type='CUSTOMER_SUPPLIED',"
				+ "stock_owner_code='CUST-1' where id=8");
		session.clearCache();
		assertThrows(CheckedException.class, () -> service.getSumByParams(query));

		jdbc.update("update wms_live_stock set mat_unit=null,source_business_no='OTHER-ORDER' where id=8");
		session.clearCache();
		assertThrows(CheckedException.class, () -> service.getSumByParams(query));

		jdbc.update("update wms_live_stock set source_business_no='CUSTOMER-ORDER-1',"
				+ "supplier_code='S1' where id=8");
		session.clearCache();
		assertEquals(3d, service.getSumByParams(query).getMatNum());
	}

	@Test
	void mobileStockSumRejectsSerialManagedStockWithoutExactPhysicalSelection() {
		ReflectionTestUtils.setField(service, "baseMapper", mapper);
		jdbc.update("update wms_live_stock set serial_managed=true,mat_num=1 where id=1");
		session.clearCache();
		var query = new WmsLiveStockQuerySumDTO();
		query.setWarehouseCode("W1");
		query.setSiteCode("L1");
		query.setMatCode("M1");
		query.setMatBatch("B1");

		assertThrows(CheckedException.class, () -> service.getSumByParams(query));
	}

	@Test
	void mobileStockSumSelectsExactlyOneSerialByRawBarcode() {
		ReflectionTestUtils.setField(service, "baseMapper", mapper);
		jdbc.update("update wms_live_stock set serial_managed=true,mat_num=1 where id=1");
		jdbc.update("insert into wms_live_stock(id,tenant_id,factory_code,warehouse_code,mat_code,"
				+ "mat_batch,site_code,supplier_code,mat_barcode,mat_num,status,serial_managed,"
				+ "stock_owner_type,stock_owner_code,source_business_no,del_flag) values"
				+ "(8,1,'F1','W1','M1','B1','L1','S1','SERIAL-2',1,'0',true,"
				+ "'CONSIGNED','SUPPLIER-1','SUPPLIER-ORDER-2','0')");
		session.clearCache();
		var query = new WmsLiveStockQuerySumDTO();
		query.setWarehouseCode("W1");
		query.setSiteCode("L1");
		query.setMatCode("M1");
		query.setMatBatch("B1");
		query.setMatBarcode("SERIAL-1");

		var selected = service.getSumByParams(query);
		assertEquals("1", selected.getSourceStockId());
		assertEquals("SERIAL-1", selected.getMatBarcode());
		assertEquals(Boolean.TRUE, selected.getSerialManaged());
		assertEquals(1d, selected.getMatNum());

		query.setMatBarcode("SERIAL-UNKNOWN");
		assertThrows(CheckedException.class, () -> service.getSumByParams(query));
		query.setMatBarcode(null);
		assertThrows(CheckedException.class, () -> service.getSumByParams(query));
		jdbc.update("insert into wms_live_stock(id,tenant_id,factory_code,warehouse_code,mat_code,"
				+ "mat_batch,site_code,supplier_code,mat_barcode,mat_num,status,serial_managed,"
				+ "stock_owner_type,stock_owner_code,source_business_no,del_flag) values"
				+ "(9,1,'F1','W1','M1','B1','L1','S1','SERIAL-1',1,'0',true,"
				+ "'CUSTOMER_SUPPLIED','CUST-1','CUSTOMER-ORDER-1','0')");
		session.clearCache();
		query.setMatBarcode("SERIAL-1");
		assertThrows(CheckedException.class, () -> service.getSumByParams(query));
	}

	@Test
	void mobileStockSumExcludesLockedSourceStock() {
		ReflectionTestUtils.setField(service, "baseMapper", mapper);
		jdbc.update("insert into wms_live_stock(id,tenant_id,factory_code,warehouse_code,mat_code,"
				+ "mat_batch,site_code,supplier_code,mat_num,status,stock_owner_type,stock_owner_code,"
				+ "source_business_no,del_flag) values"
				+ "(8,1,'F1','W1','M1','B1','L1','S1',5,'1','CUSTOMER_SUPPLIED','CUST-1',"
				+ "'CUSTOMER-ORDER-1','0')");
		session.clearCache();
		var query = new WmsLiveStockQuerySumDTO();
		query.setWarehouseCode("W1");
		query.setSiteCode("L1");
		query.setMatCode("M1");
		query.setMatBatch("B1");

		assertEquals(1d, service.getSumByParams(query).getMatNum());
	}

	@Test
	void mobileStockSumSeparatesPhysicalAndUnreservedQuantity() {
		ReflectionTestUtils.setField(service, "baseMapper", mapper);
		jdbc.update("update wms_live_stock set mat_num=3 where id=1");
		jdbc.update("insert into wms_live_stock(id,tenant_id,factory_code,warehouse_code,mat_code,"
				+ "mat_batch,site_code,supplier_code,mat_num,status,stock_owner_type,stock_owner_code,"
				+ "source_business_no,del_flag) values"
				+ "(8,1,'F1','W1','M1','B1','L1','S1',4,'0','CUSTOMER_SUPPLIED','CUST-1',"
				+ "'CUSTOMER-ORDER-1','0')");
		session.clearCache();
		var productionReservations = (WmsMaterialReservationService) ReflectionTestUtils.getField(service,
				"wmsMaterialReservationService");
		var salesReservations = (WmsSalesStockReservationService) ReflectionTestUtils.getField(service,
				"wmsSalesStockReservationService");
		when(productionReservations.activeReservedNums(Set.of(1L, 8L)))
				.thenReturn(Map.of(1L, new BigDecimal("2")));
		when(salesReservations.activeReservedNums(Set.of(1L, 8L)))
				.thenReturn(Map.of(8L, new BigDecimal("1")));
		var query = new WmsLiveStockQuerySumDTO();
		query.setWarehouseCode("W1");
		query.setSiteCode("L1");
		query.setMatCode("M1");
		query.setMatBatch("B1");

		var result = service.getSumByParams(query);
		assertEquals(7d, result.getMatNum());
		assertEquals(0, new BigDecimal("4").compareTo(result.getOperationAvailableNum()));
		verify(productionReservations).activeReservedNums(Set.of(1L, 8L));
		verify(salesReservations).activeReservedNums(Set.of(1L, 8L));

		when(productionReservations.activeReservedNums(Set.of(1L, 8L)))
				.thenReturn(Map.of(1L, new BigDecimal("20"), 8L, new BigDecimal("20")));
		var fullyReserved = service.getSumByParams(query);
		assertEquals(7d, fullyReserved.getMatNum());
		assertEquals(0, BigDecimal.ZERO.compareTo(fullyReserved.getOperationAvailableNum()));
	}

	@Test
	void stockSelectionAndMobileSumRejectMissingOrganizationContext() {
		var selection = new WmsLiveStockQueryDTO().setWarehouseCode("W1");
		var sum = new WmsLiveStockQuerySumDTO();
		sum.setWarehouseCode("W1");

		TenantContextHolder.clear();
		assertThrows(CheckedException.class, () -> service.pageWhenOut(new Page<>(1, 10), selection));
		assertThrows(CheckedException.class, () -> service.getSumByParams(sum));

		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.clear();
		assertThrows(CheckedException.class, () -> service.pageWhenOut(new Page<>(1, 10), selection));
		assertThrows(CheckedException.class, () -> service.getSumByParams(sum));

		FactoryContextHolder.setFactoryCode("F1");
		selection.setWarehouseCode("W*");
		sum.setWarehouseCode("W*");
		assertThrows(CheckedException.class, () -> service.pageWhenOut(new Page<>(1, 10), selection));
		assertThrows(CheckedException.class, () -> service.getSumByParams(sum));
	}

	@Test
	void noticeStockSelectionOnlyUsesDetailsFromCurrentWarehouseAndOrganization() {
		ReflectionTestUtils.setField(service, "baseMapper", mapper);
		ReflectionTestUtils.setField(service, "wmsOutNoticeDetailMapper", noticeMapper);
		jdbc.update("insert into wms_out_notice_detail values "
				+ "(11,1,'F1',100,'W1','M1','0'),"
				+ "(12,2,'F1',100,'W1','M2','0'),"
				+ "(13,1,'F2',100,'W1','M2','0'),"
				+ "(14,1,'F1',100,'W2','M2','0')");
		jdbc.update("insert into wms_out_notice values (100,1,'F1','W1','0','0')");
		jdbc.update("insert into wms_live_stock(id,tenant_id,factory_code,warehouse_code,mat_code,mat_name,"
				+ "mat_batch,site_code,mat_num,status,is_quality_test,del_flag) values "
				+ "(6,1,'F1','W1','M2','Wrong notice item','B2','L2',3,'0','0','0')");
		jdbc.update("update wms_live_stock set is_quality_test='0',quality_result=null,quality_status=null where id=2");
		session.clearCache();
		var query = new WmsLiveStockQueryDTO().setWarehouseCode("W1").setNoticeId(100L);

		var grouped = service.pageWhenOut(new Page<>(1, 10), query);
		assertEquals(1, grouped.getTotal());
		assertEquals("M1", grouped.getRecords().get(0).getMatCode());

		query.setExactStock(true);
		var exactService = spy(service);
		var observed = new AtomicReference<Wrapper<WmsLiveStockEntity>>();
		doAnswer(call -> {
			observed.set(call.getArgument(1));
			return new Page<WmsLiveStockEntity>(1, 10);
		}).when(exactService).page(any(Page.class), any(Wrapper.class));
		exactService.pageWhenOut(new Page<>(1, 10), query);
		assertTrue(observed.get().getSqlSegment().contains("mat_code IN"));
		var values = ((LambdaQueryWrapper<WmsLiveStockEntity>) observed.get()).getParamNameValuePairs().values();
		assertTrue(values.contains("M1"));
		assertFalse(values.contains("M2"));
	}

	@Test
	void noticeStockSelectionRequiresUsableMasterInSameOrganizationAndWarehouse() {
		ReflectionTestUtils.setField(service, "baseMapper", mapper);
		ReflectionTestUtils.setField(service, "wmsOutNoticeDetailMapper", noticeMapper);
		jdbc.update("insert into wms_out_notice_detail values (21,1,'F1',200,'W1','M1','0')");
		var query = new WmsLiveStockQueryDTO().setWarehouseCode("W1").setNoticeId(200L);

		assertThrows(CheckedException.class, () -> service.pageWhenOut(new Page<>(1, 10), query));

		jdbc.update("insert into wms_out_notice values (200,2,'F1','W1','0','0')");
		session.clearCache();
		assertThrows(CheckedException.class, () -> service.pageWhenOut(new Page<>(1, 10), query));
		jdbc.update("update wms_out_notice set tenant_id=1,factory_code='F2' where id=200");
		session.clearCache();
		assertThrows(CheckedException.class, () -> service.pageWhenOut(new Page<>(1, 10), query));
		jdbc.update("update wms_out_notice set factory_code='F1',warehouse_code='W2' where id=200");
		session.clearCache();
		assertThrows(CheckedException.class, () -> service.pageWhenOut(new Page<>(1, 10), query));
		jdbc.update("update wms_out_notice set warehouse_code='W1',state='2' where id=200");
		session.clearCache();
		assertThrows(CheckedException.class, () -> service.pageWhenOut(new Page<>(1, 10), query));
		jdbc.update("update wms_out_notice set state='3' where id=200");
		session.clearCache();
		assertThrows(CheckedException.class, () -> service.pageWhenOut(new Page<>(1, 10), query));

		jdbc.update("update wms_live_stock set is_quality_test='0',quality_result=null,quality_status=null where id=2");
		jdbc.update("update wms_out_notice set state='0' where id=200");
		session.clearCache();
		assertEquals(1, service.pageWhenOut(new Page<>(1, 10), query).getTotal());
		jdbc.update("update wms_out_notice set state='1' where id=200");
		session.clearCache();
		assertEquals(1, service.pageWhenOut(new Page<>(1, 10), query).getTotal());
	}

	private Page<WmsLiveStockEntity> page(WmsLiveStockQueryDTO query, long current, long size) {
		// Narrow only the selected columns for the fixture; execute the production WHERE/ORDER and actual mapper.
		var wrapper = service.getDetailListQueryWrapper(query).select(WmsLiveStockEntity::getId,
				WmsLiveStockEntity::getTenantId, WmsLiveStockEntity::getFactoryCode,
				WmsLiveStockEntity::getWarehouseCode,
				WmsLiveStockEntity::getMatCode, WmsLiveStockEntity::getMatNum);
		return mapper.selectPage(new Page<>(current, size), wrapper);
	}

	private static List<Long> ids(Page<WmsLiveStockEntity> page) {
		return page.getRecords().stream().map(WmsLiveStockEntity::getId).toList();
	}
}
