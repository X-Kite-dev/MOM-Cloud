package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.mapper.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 拣货实际业务方法与Spring事务回归，使用JDBC适配Mapper持久化边界。
 * <p>
 * 测试旧实现时，仅覆盖框架saveBatch存储方法；业务方法保持真实执行。
 */
class WmsPickingWriteBoundaryTest {
	private JdbcTemplate db;
	private WmsPickingAreaServiceImpl service;
	private WmsPickingEventMapper eventMapper;
	private boolean zeroMerge, zeroInsert, zeroRemove, zeroFlow, zeroEvent;
	private long nextPicking = 100, nextFlow = 200, nextEvent = 300;

	@BeforeEach
	void setup() {
		var ds = new DriverManagerDataSource("jdbc:h2:mem:picking-write-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
				"sa", "");
		ds.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(ds);
		db.execute("create table picking(id bigint primary key,bill_no varchar,bill_id bigint,bill_detail_id bigint,"
				+ "tenant_id bigint,factory_code varchar,warehouse_code varchar,"
				+ "site_code varchar,mat_code varchar,mat_batch varchar,"
				+ "container_code varchar,supplier_code varchar,mat_num double,"
				+ "source_stock_id bigint,source_in_bill_detail_id bigint,mat_barcode varchar,"
				+ "stock_owner_type varchar,stock_owner_code varchar,source_business_no varchar,"
				+ "source_in_bill_id bigint,source_mes_inbound_request_id varchar,serial_managed boolean)");
		db.execute("create table flow(id bigint primary key,bill_no varchar,bill_id bigint,bill_detail_id bigint,"
				+ "tenant_id bigint,factory_code varchar,warehouse_code varchar,"
				+ "site_code varchar,mat_code varchar,mat_num double,source_stock_id bigint,"
				+ "source_in_bill_detail_id bigint,mat_barcode varchar,stock_owner_type varchar,"
				+ "stock_owner_code varchar,source_business_no varchar,stock_proof_type varchar,"
				+ "operation_request_id varchar,source_in_bill_id bigint,"
				+ "source_mes_inbound_request_id varchar,serial_managed boolean)");
		db.execute("create table picking_event(id bigint primary key,bill_no varchar,bill_detail_id bigint,"
				+ "tenant_id bigint,factory_code varchar,warehouse_code varchar,site_code varchar,"
				+ "source_stock_id bigint,source_in_bill_detail_id bigint,mat_code varchar,mat_batch varchar,"
				+ "mat_barcode varchar,mat_num double,stock_owner_type varchar,picked_at timestamp)");
		db.update("insert into picking(id,bill_no,bill_id,bill_detail_id,tenant_id,factory_code,warehouse_code,site_code,"
				+ "mat_code,mat_batch,container_code,supplier_code,mat_num,source_stock_id,"
				+ "source_in_bill_detail_id,mat_barcode,stock_owner_type,source_business_no)"
				+ " values(1,'OUT-1',20,40,1,'F1','W1','S1','M1','B1','C1','SUP1',1,10,81,'BC-10','OWNED','RK-1')");
		db.update("insert into picking(id,bill_no,tenant_id,factory_code,warehouse_code,site_code,"
				+ "mat_code,mat_batch,container_code,supplier_code,mat_num)"
				+ " values(2,'OUT-2',1,'F1','W1','S2','M2','B2','C2','SUP2',99)");
		db.update("insert into picking(id,bill_no,tenant_id,factory_code,warehouse_code,site_code,"
				+ "mat_code,mat_batch,container_code,supplier_code,mat_num)"
				+ " values(3,'OUT-1',2,'F2','W1','S1','M1','B1','C1','SUP1',55)");
		for (Class<?> type : List.of(WmsPickingAreaEntity.class, WmsInoutFlowEntity.class)) {
			TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "picking-write"),
					type);
		}
		var mapper = mock(WmsPickingAreaMapper.class);
		when(mapper.selectList(any(Wrapper.class))).thenAnswer(call -> {
			Wrapper<?> wrapper = call.getArgument(0);
			var sql = bind("select * from picking " + wrapper.getCustomSqlSegment(), wrapper);
			return db.query(sql.text(), (r, n) -> {
				var p = new WmsPickingAreaEntity();
				p.setId(r.getLong("id"));
				p.setBillNo(r.getString("bill_no"));
				p.setBillId((Long) r.getObject("bill_id"));
				p.setBillDetailId((Long) r.getObject("bill_detail_id"));
				p.setTenantId(r.getLong("tenant_id"));
				p.setFactoryCode(r.getString("factory_code"));
				p.setWarehouseCode(r.getString("warehouse_code"));
				p.setSiteCode(r.getString("site_code"));
				p.setMatCode(r.getString("mat_code"));
				p.setMatBatch(r.getString("mat_batch"));
				p.setContainerCode(r.getString("container_code"));
				p.setSupplierCode(r.getString("supplier_code"));
				p.setMatNum((Double) r.getObject("mat_num"));
				p.setSourceStockId((Long) r.getObject("source_stock_id"));
				p.setSourceInBillDetailId((Long) r.getObject("source_in_bill_detail_id"));
				p.setMatBarcode(r.getString("mat_barcode"));
				p.setStockOwnerType(r.getString("stock_owner_type"));
				p.setStockOwnerCode(r.getString("stock_owner_code"));
				p.setSourceBusinessNo(r.getString("source_business_no"));
				p.setSourceInBillId((Long) r.getObject("source_in_bill_id"));
				p.setSourceMesInboundRequestId(r.getString("source_mes_inbound_request_id"));
				p.setSerialManaged((Boolean) r.getObject("serial_managed"));
				return p;
			}, sql.values());
		});
		when(mapper.selectOne(any(Wrapper.class), anyBoolean())).thenCallRealMethod();
		when(mapper.update(any(Wrapper.class))).thenCallRealMethod();
		when(mapper.update(isNull(), any(Wrapper.class))).thenAnswer(call -> {
			if (zeroMerge) {
				return 0;
			}
			LambdaUpdateWrapper<?> wrapper = call.getArgument(1);
			var sql = bind("update picking set " + wrapper.getSqlSet() + " " + wrapper.getCustomSqlSegment(), wrapper);
			return db.update(sql.text(), sql.values());
		});
		when(mapper.insert(any(WmsPickingAreaEntity.class))).thenAnswer(call -> insert(call.getArgument(0)));
		when(mapper.delete(any(Wrapper.class))).thenAnswer(call -> {
			if (zeroRemove) {
				return 0;
			}
			Wrapper<?> wrapper = call.getArgument(0);
			var sql = bind("delete from picking " + wrapper.getCustomSqlSegment(), wrapper);
			return db.update(sql.text(), sql.values());
		});
		var flows = mock(WmsInoutFlowMapper.class);
		when(flows.insert(any(WmsInoutFlowEntity.class))).thenAnswer(call -> flow(call.getArgument(0)));
		when(flows.insert(anyCollection())).thenAnswer(call -> {
			Collection<WmsInoutFlowEntity> rows = call.getArgument(0);
			rows.forEach(this::flow);
			return null;
		});
		eventMapper = mock(WmsPickingEventMapper.class);
		when(eventMapper.insert(any(WmsPickingEventEntity.class))).thenAnswer(call -> {
			if (zeroEvent) return 0;
			WmsPickingEventEntity row = call.getArgument(0);
			return db.update("insert into picking_event values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
					nextEvent++, row.getBillNo(), row.getBillDetailId(), row.getTenantId(), row.getFactoryCode(),
					row.getWarehouseCode(), row.getSiteCode(), row.getSourceStockId(),
					row.getSourceInBillDetailId(), row.getMatCode(), row.getMatBatch(), row.getMatBarcode(),
					row.getMatNum(), row.getStockOwnerType(), row.getPickedAt());
		});
		var target = new WmsPickingAreaServiceImpl(flows, eventMapper, null) {
			@Override
			public boolean saveBatch(Collection<WmsPickingAreaEntity> rows, int batchSize) {
				boolean saved = true;
				for (var row : rows) {
					saved &= insert(row) == 1;
				}
				return saved;
			}
		};
		ReflectionTestUtils.setField(target, "baseMapper", mapper);
		var proxy = new ProxyFactory(target);
		proxy.setProxyTargetClass(true);
		proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),
				new AnnotationTransactionAttributeSource()));
		service = (WmsPickingAreaServiceImpl) proxy.getProxy();
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
	}

	@AfterEach
	void clear() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	private int insert(WmsPickingAreaEntity row) {
		if (zeroInsert) {
			return 0;
		}
		row.setId(nextPicking++);
		return db.update("insert into picking(id,bill_no,bill_id,bill_detail_id,tenant_id,factory_code,warehouse_code,site_code,"
				+ "mat_code,mat_batch,container_code,supplier_code,mat_num,source_stock_id,"
				+ "source_in_bill_detail_id,mat_barcode,stock_owner_type,stock_owner_code,source_business_no,"
				+ "source_in_bill_id,source_mes_inbound_request_id,serial_managed)"
				+ " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", row.getId(), row.getBillNo(),
				row.getBillId(), row.getBillDetailId(),
				row.getTenantId(), row.getFactoryCode(),
				row.getWarehouseCode(), row.getSiteCode(), row.getMatCode(), row.getMatBatch(), row.getContainerCode(),
				row.getSupplierCode(), row.getMatNum(), row.getSourceStockId(), row.getSourceInBillDetailId(),
				row.getMatBarcode(), row.getStockOwnerType(), row.getStockOwnerCode(), row.getSourceBusinessNo(),
				row.getSourceInBillId(), row.getSourceMesInboundRequestId(), row.getSerialManaged());
	}

	private int flow(WmsInoutFlowEntity row) {
		if (zeroFlow) {
			return 0;
		}
		return db.update("insert into flow values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", nextFlow++, row.getBillNo(),
				row.getBillId(), row.getBillDetailId(),
				row.getTenantId(), row.getFactoryCode(), row.getWarehouseCode(), row.getSiteCode(), row.getMatCode(),
				row.getMatNum(), row.getSourceStockId(), row.getSourceInBillDetailId(), row.getMatBarcode(),
				row.getStockOwnerType(), row.getStockOwnerCode(), row.getSourceBusinessNo(),
				row.getStockProofType(), row.getOperationRequestId(), row.getSourceInBillId(),
				row.getSourceMesInboundRequestId(), row.getSerialManaged());
	}

	private record Bound(String text, Object[] values) {
	}

	private Bound bind(String sql, Wrapper<?> wrapper) {
		var matcher = Pattern.compile("#\\{ew\\.paramNameValuePairs\\.(\\w+)(?:,[^}]*)?}").matcher(sql);
		List<Object> values = new ArrayList<>();
		StringBuilder result = new StringBuilder();
		Map<String, Object> params = ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
		while (matcher.find()) {
			values.add(params.get(matcher.group(1)));
			matcher.appendReplacement(result, "?");
		}
		matcher.appendTail(result);
		return new Bound(result.toString(), values.toArray());
	}

	private WmsLiveStockEntity stock(double number) {
		return stock(10L, number);
	}

	private WmsLiveStockEntity stock(long id, double number) {
		var row = new WmsLiveStockEntity().setFactoryCode("F1").setWarehouseCode("W1").setSiteCode("S1")
				.setMatCode("M1")
				.setMatBatch("B1").setContainerCode("C1").setSupplierCode("SUP1").setMatNum(number)
				.setSourceInBillDetailId(id == 10L ? 81L : 82L)
				.setMatBarcode("BC-" + id).setStockOwnerType("OWNED").setSourceBusinessNo("RK-1");
		row.setId(id);
		row.setTenantId(1L);
		return row;
	}

	private WmsOutBillEntity bill() {
		var row = new WmsOutBillEntity();
		row.setId(20L);
		row.setBillNo("OUT-1");
		row.setBillType("other_retail");
		row.setFactoryCode("F1");
		row.setWarehouseCode("W1");
		row.setTenantId(1L);
		return row;
	}

	private double quantity(long id) {
		return db.queryForObject("select mat_num from picking where id=?", Double.class, id);
	}

	private int count(String table) {
		return db.queryForObject("select count(*) from " + table, Integer.class);
	}

	private void original() {
		assertEquals(3, count("picking"));
		assertEquals(1d, quantity(1));
		assertEquals(99d, quantity(2));
		assertEquals(55d, quantity(3));
		assertEquals(0, count("flow"));
	}

	@Test
	void mergeUpdatesOnlyTheOriginalRowAndActualOrganization() {
		assertTrue(service.insertByStockList(List.of(stock(2)), bill(), 40L));

		assertEquals(3d, quantity(1));
		assertEquals(99d, quantity(2));
		assertEquals(55d, quantity(3));
		assertEquals(1, count("picking_event"));
		assertEquals(2d, db.queryForObject("select mat_num from picking_event", Double.class));
	}

	@Test
	void eachPickingOperationKeepsItsOwnEventAfterOutboundCompletion() {
		db.update("delete from picking");

		assertTrue(service.insertByStockList(List.of(stock(2), stock(3)), bill(), 40L));
		assertTrue(service.deleteByOutBill(bill()));

		assertEquals(0, count("picking"));
		assertEquals(1, count("flow"));
		assertEquals(2, count("picking_event"));
		assertEquals(5d, db.queryForObject("select sum(mat_num) from picking_event", Double.class));
		assertEquals(81L, db.queryForObject("select source_in_bill_detail_id from picking_event limit 1", Long.class));
	}

	@Test
	void failedEventWriteRollsBackPickingMutation() {
		zeroEvent = true;

		assertThrows(CheckedException.class, () -> service.insertByStockList(List.of(stock(2)), bill(), 40L));

		original();
		assertEquals(0, count("picking_event"));
	}

	@Test
	void newRowsFromOneReceiptMergeWithoutCreatingDuplicatePickingIdentities() {
		db.update("delete from picking");

		assertTrue(service.insertByStockList(List.of(stock(2), stock(3)), bill(), 40L));

		assertEquals(1, count("picking"));
		assertEquals(5d, db.queryForObject("select mat_num from picking", Double.class));
	}

	@Test
	void sameBatchFromDifferentStockRowsKeepsSeparatePickingAndOutboundProof() {
		db.update("delete from picking");

		assertTrue(service.insertByStockList(List.of(stock(10L, 2), stock(11L, 3)), bill(), 40L));
		assertEquals(2, count("picking"));
		assertEquals(2d, db.queryForObject(
				"select mat_num from picking where source_stock_id=10", Double.class));
		assertEquals(3d, db.queryForObject(
				"select mat_num from picking where source_stock_id=11", Double.class));

		assertTrue(service.deleteByOutBill(bill()));
		assertEquals(2, count("flow"));
		assertEquals(81L, db.queryForObject(
				"select source_in_bill_detail_id from flow where source_stock_id=10", Long.class));
		assertEquals("BC-11", db.queryForObject(
				"select mat_barcode from flow where source_stock_id=11", String.class));
		assertEquals("OWNED", db.queryForObject(
				"select stock_owner_type from flow where source_stock_id=11", String.class));
		assertEquals(40L, db.queryForObject(
				"select bill_detail_id from flow where source_stock_id=11", Long.class));
		assertEquals("OUTBOUND", db.queryForObject(
				"select stock_proof_type from flow where source_stock_id=11", String.class));
		assertEquals("OUT-STOCK:40:11", db.queryForObject(
				"select operation_request_id from flow where source_stock_id=11", String.class));
	}

	@Test
	void sameStockOnDifferentOutboundDetailsKeepsEachDetailProof() {
		db.update("delete from picking");

		assertTrue(service.insertByStockList(List.of(stock(2)), bill(), 40L));
		assertTrue(service.insertByStockList(List.of(stock(3)), bill(), 41L));
		assertEquals(2, count("picking"));

		assertTrue(service.deleteByOutBill(bill()));
		assertEquals(2, count("flow"));
		assertEquals(2d, db.queryForObject(
				"select mat_num from flow where bill_detail_id=40", Double.class));
		assertEquals(3d, db.queryForObject(
				"select mat_num from flow where bill_detail_id=41", Double.class));
	}

	@Test
	void serialAndMesInboundOriginSurvivePickingAndOutboundFlow() {
		db.update("delete from picking");
		var serial = stock(11L, 1).setSourceInBillId(80L)
				.setSourceMesInboundRequestId("MES-1").setSerialManaged(true);

		assertTrue(service.insertByStockList(List.of(serial), bill(), 40L));
		assertTrue(service.deleteByOutBill(bill()));

		assertEquals(80L, db.queryForObject("select source_in_bill_id from flow", Long.class));
		assertEquals("MES-1", db.queryForObject(
				"select source_mes_inbound_request_id from flow", String.class));
		assertEquals(true, db.queryForObject("select serial_managed from flow", Boolean.class));
		var captured = org.mockito.ArgumentCaptor.forClass(WmsPickingEventEntity.class);
		verify(eventMapper).insert(captured.capture());
		assertEquals("MES-1", captured.getValue().getSourceMesInboundRequestId());
		assertEquals(80L, captured.getValue().getSourceInBillId());
		assertEquals(true, captured.getValue().getSerialManaged());
	}

	@Test
	void legacyPickingWithoutStockIdCannotAbsorbNewPhysicalStock() {
		db.update("update picking set source_stock_id=null where id=1");

		assertTrue(service.insertByStockList(List.of(stock(2)), bill(), 40L));

		assertEquals(4, count("picking"));
		assertEquals(1d, quantity(1));
		assertEquals(2d, db.queryForObject(
				"select mat_num from picking where source_stock_id=10", Double.class));
	}

	@Test
	void legacyPickingWithoutExactSourceDoesNotClaimStockProof() {
		db.update("update picking set source_stock_id=null,bill_id=null,bill_detail_id=null where id=1");

		assertTrue(service.deleteByOutBill(bill()));

		assertNull(db.queryForObject("select stock_proof_type from flow", String.class));
		assertNull(db.queryForObject("select operation_request_id from flow", String.class));
	}

	@Test
	void sameStockIdWithConflictingBarcodeCannotMergePickingProof() {
		db.update("update picking set mat_barcode='WRONG' where id=1");

		assertThrows(CheckedException.class, () -> service.insertByStockList(List.of(stock(2)), bill(), 40L));
		original();
	}

	@Test
	void sameStockIdWithConflictingLocationCannotCreateAnotherPickingIdentity() {
		var moved = stock(2);
		moved.setSiteCode("S9");

		assertThrows(CheckedException.class, () -> service.insertByStockList(List.of(moved), bill(), 40L));
		original();
	}

	@Test
	void zeroMergeWriteCannotBeReportedAsSuccess() {
		zeroMerge = true;

		assertThrows(CheckedException.class, () -> service.insertByStockList(List.of(stock(2)), bill(), 40L));

		original();
	}

	@Test
	void zeroNewInsertCannotBeReportedAsSuccess() {
		db.update("delete from picking where id=1");
		zeroInsert = true;

		assertThrows(CheckedException.class, () -> service.insertByStockList(List.of(stock(2)), bill(), 40L));

		assertEquals(2, count("picking"));
		assertEquals(99d, quantity(2));
		assertEquals(55d, quantity(3));
	}

	@Test
	void failedRemovalCannotGenerateAnOutboundFlow() {
		zeroRemove = true;

		assertThrows(CheckedException.class, () -> service.deleteByOutBill(bill()));

		original();
	}

	@Test
	void completionCannotConsumeAnotherBillsPickingRowWithSameNumber() {
		db.update("update picking set bill_id=999 where id=1");

		assertThrows(CheckedException.class, () -> service.deleteByOutBill(bill()));

		assertEquals(3, count("picking"));
		assertEquals(0, count("flow"));
	}

	@Test
	void failedFlowInsertRollsBackPickingDeletion() {
		zeroFlow = true;

		assertThrows(CheckedException.class, () -> service.deleteByOutBill(bill()));

		original();
	}

	@Test
	void invalidPickingQuantityCannotMutateAnyRows() {
		for (double value : new double[] { 0, -1, Double.NaN, Double.POSITIVE_INFINITY }) {
			assertThrows(CheckedException.class, () -> service.insertByStockList(List.of(stock(value)), bill(), 40L));
			original();
		}
	}

	@Test
	void completionDeletesOnlyTheOriginalRowsAndWritesTheirActualFlow() {
		assertTrue(service.deleteByOutBill(bill()));

		assertEquals(2, count("picking"));
		assertEquals(99d, quantity(2));
		assertEquals(55d, quantity(3));
		assertEquals(1, count("flow"));
		assertEquals(1d, db.queryForObject("select mat_num from flow", Double.class));
		assertEquals("OUT-1", db.queryForObject("select bill_no from flow", String.class));
	}
}
