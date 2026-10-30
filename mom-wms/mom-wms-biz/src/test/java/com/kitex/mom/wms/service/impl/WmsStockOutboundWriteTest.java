package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.WmsMaterialReservationService;
import com.kitex.mom.wms.service.WmsSalesStockReservationService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 普通与冻结库存的实际扣减及事务回归。
 * <p>
 * 执行真实业务方法、容器处理和Spring事务，以JDBC在H2上执行实际Mapper条件；MDM和预留数量作为适配边界。
 * <p>
 * 不覆盖真实PG、组织拦截器、完整预留消费、HTTP及跨请求并发。
 */
class WmsStockOutboundWriteTest {
	private JdbcTemplate db;
	private WmsLiveStockServiceImpl service;
	private WmsMaterialReservationService production;
	private long zeroUpdateId = -1, zeroDeleteId = -1;

	@BeforeEach
	void setup() {
		// 为每项回归建立独立数据库，准备原库存、其他库存及容器使用历史。
		var ds = new DriverManagerDataSource("jdbc:h2:mem:outbound-stock-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
				"sa", "");
		ds.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(ds);
		db.execute("create table stock(id bigint primary key,tenant_id bigint,"
				+ "factory_code varchar,warehouse_code varchar,site_code varchar,"
				+ "container_code varchar,mat_code varchar,mat_unit varchar,"
				+ "mat_batch varchar,supplier_code varchar,mat_num double,status "
				+ "int,in_time timestamp,source_in_bill_id bigint,stock_owner_type varchar,"
				+ "stock_owner_code varchar,source_business_no varchar)");
		db.execute("create table container(code varchar primary key,is_used int)");
		db.execute("create table container_history(code varchar,mat_code varchar,end_time timestamp)");
		addStock(1, 10);
		addStock(99, 99);
		db.update("update stock set mat_code='OTHER',container_code='OTHER',site_code='OTHER' where id=99");
		db.update("insert into container values('C1',1)");
		db.update("insert into container_history values('C1','M1',null)");
		for (Class<?> type : List.of(WmsLiveStockEntity.class, WmsContainerBarcodeEntity.class,
				WmsContainerUseHistoryEntity.class)) {
			TableInfoHelper.initTableInfo(
					new MapperBuilderAssistant(new MybatisConfiguration(), "stock-outbound-write"), type);
		}

		// Mapper边界执行真实筛选条件，并允许指定原库存行返回零行写入。
		var mapper = mock(WmsLiveStockMapper.class);
		when(mapper.selectList(any(Wrapper.class))).thenAnswer(call -> stocks(call.getArgument(0)));
		when(mapper.selectOne(any(Wrapper.class), eq(false))).thenAnswer(call -> {
			var rows = stocks(call.getArgument(0));
			return rows.isEmpty() ? null : rows.get(0);
		});
		when(mapper.update(any(Wrapper.class))).thenCallRealMethod();
		when(mapper.update(isNull(), any(Wrapper.class))).thenAnswer(call -> {
			Wrapper<?> wrapper = call.getArgument(1);
			if (stocks(wrapper).stream().anyMatch(row -> Objects.equals(row.getId(), zeroUpdateId))) {
				return 0;
			}
			return update("stock", wrapper);
		});
		when(mapper.deleteById(any(java.io.Serializable.class))).thenAnswer(call -> {
			Long id = call.getArgument(0);
			return id == zeroDeleteId ? 0 : db.update("delete from stock where id=?", id);
		});

		var containers = mock(WmsContainerBarcodeMapper.class);
		when(containers.update(any(Wrapper.class))).thenCallRealMethod();
		when(containers.update(isNull(), any(Wrapper.class)))
				.thenAnswer(call -> update("container", call.getArgument(1)));
		var history = mock(WmsContainerUseHistoryMapper.class);
		when(history.update(any(Wrapper.class))).thenCallRealMethod();
		when(history.update(isNull(), any(Wrapper.class)))
				.thenAnswer(call -> update("container_history", call.getArgument(1)));

		// 外部主数据和活动预留量不在本组测试中启动实际服务。
		var material = mock(RemoteMaterialService.class);
		when(material.getDetails("M1")).thenReturn(R.ok(new MdmMaterialEntity()));
		production = mock(WmsMaterialReservationService.class);
		when(production.activeReservedNum(anyLong())).thenReturn(BigDecimal.ZERO);
		var sales = mock(WmsSalesStockReservationService.class);
		when(sales.activeReservedNums(anySet())).thenReturn(Map.of(1L, BigDecimal.ZERO, 2L, BigDecimal.ZERO));

		// 业务服务与JDBC适配共用数据源，通过真实事务代理验证失败回滚。
		var target = new WmsLiveStockServiceImpl(material, containers, history, null, null, null, null, production,
				sales, null);
		ReflectionTestUtils.setField(target, "baseMapper", mapper);
		var proxy = new ProxyFactory(target);
		proxy.setProxyTargetClass(true);
		proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),
				new AnnotationTransactionAttributeSource()));
		service = (WmsLiveStockServiceImpl) proxy.getProxy();
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
	}

	@AfterEach
	void clear() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	private void addStock(long id, double quantity) {
		db.update("insert into stock(id,tenant_id,factory_code,warehouse_code,site_code,container_code,"
				+ "mat_code,mat_unit,mat_batch,supplier_code,mat_num,status,in_time,source_in_bill_id) "
				+ "values(?,1,'F1','W1','S1','C1','M1','KG','B1','SUP',?,0,"
				+ "DATEADD('SECOND',?,timestamp '2026-10-23 00:00:00'),365)", id, quantity, id);
	}

	private WmsOutBillDetailEntity command(Double quantity, boolean frozen) {
		var row = new WmsOutBillDetailEntity().setFactoryCode("F1").setWarehouseCode("W1").setSiteCode("S1")
				.setContainerCode("C1").setMatCode("M1").setMatUnit("KG").setMatBatch("B1").setSupplierCode("SUP")
				.setMatNum(quantity).setSourceStockId(frozen ? 1L : null);
		row.setTenantId(1L);
		return row;
	}

	private record Bound(String sql, Object[] values) {
	}

	private Bound bind(String sql, Wrapper<?> wrapper) {
		var matcher = Pattern.compile("#\\{ew\\.paramNameValuePairs\\.(\\w+)(?:,[^}]*)?}").matcher(sql);
		var values = new ArrayList<Object>();
		var result = new StringBuilder();
		var parameters = ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
		while (matcher.find()) {
			values.add(parameters.get(matcher.group(1)));
			matcher.appendReplacement(result, "?");
		}
		matcher.appendTail(result);
		return new Bound(result.toString(), values.toArray());
	}

	private List<WmsLiveStockEntity> stocks(Wrapper<?> wrapper) {
		var q = bind("select * from stock " + wrapper.getCustomSqlSegment(), wrapper);
		return db.query(q.sql(), (r, n) -> {
			var s = new WmsLiveStockEntity().setFactoryCode(r.getString("factory_code"))
					.setWarehouseCode(r.getString("warehouse_code"))
					.setSiteCode(r.getString("site_code")).setContainerCode(r.getString("container_code"))
					.setMatCode(r.getString("mat_code")).setMatUnit(r.getString("mat_unit"))
					.setMatBatch(r.getString("mat_batch"))
					.setSupplierCode(r.getString("supplier_code")).setMatNum((Double) r.getObject("mat_num"))
					.setStatus(r.getInt("status")).setSourceInBillId(r.getLong("source_in_bill_id"))
					.setStockOwnerType(r.getString("stock_owner_type"))
					.setStockOwnerCode(r.getString("stock_owner_code"))
					.setSourceBusinessNo(r.getString("source_business_no"));
			s.setId(r.getLong("id"));
			s.setTenantId(r.getLong("tenant_id"));
			return s;
		}, q.values());
	}

	private int update(String table, Wrapper<?> wrapper) {
		var u = (LambdaUpdateWrapper<?>) wrapper;
		var q = bind("update " + table + " set " + u.getSqlSet() + " " + u.getCustomSqlSegment(), wrapper);
		return db.update(q.sql(), q.values());
	}

	private double quantity(long id) {
		return db.queryForObject("select mat_num from stock where id=?", Double.class, id);
	}

	private void untouched() {
		assertEquals(10d, quantity(1));
		assertEquals(99d, quantity(99));
		assertEquals(1, db.queryForObject("select is_used from container where code='C1'", Integer.class));
		assertNull(
				db.queryForObject("select end_time from container_history where code='C1'", java.sql.Timestamp.class));
	}

	private void proof(WmsLiveStockEntity row, long id, double quantity) {
		assertEquals(id, row.getId());
		assertEquals(quantity, row.getMatNum());
		assertEquals(365L, row.getSourceInBillId());
		assertEquals(1L, row.getTenantId());
		assertEquals("F1", row.getFactoryCode());
		assertEquals("W1", row.getWarehouseCode());
		assertEquals("S1", row.getSiteCode());
	}

	@Test
	void ordinaryPartialDeductionReturnsOnlyThePersistedDeltaAndOriginalIdentity() {
		var rows = service.reduceStockWhenOut(command(2d, false));

		assertEquals(1, rows.size());
		proof(rows.get(0), 1, 2);
		assertEquals(8d, quantity(1));
		assertEquals(99d, quantity(99));
	}

	@Test
	void ordinaryIssueCannotTakeCustomerOwnedStock() {
		db.update("update stock set stock_owner_type='CUSTOMER_SUPPLIED',"
				+ "stock_owner_code='CUST-1',source_business_no='CUSTOMER-ORDER-1' where id=1");

		assertThrows(CheckedException.class, () -> service.reduceStockWhenOut(command(2d, false)));
		untouched();
	}

	@Test
	void exactOtherIssueKeepsCustomerOwnershipAndSourceEvidence() {
		db.update("update stock set stock_owner_type='CUSTOMER_SUPPLIED',"
				+ "stock_owner_code='CUST-1',source_business_no='CUSTOMER-ORDER-1' where id=1");
		var detail = command(2d, true).setBillType("other_out")
				.setStockOwnerType("CUSTOMER_SUPPLIED").setStockOwnerCode("CUST-1");

		var proof = service.reduceStockWhenOut(detail);

		assertEquals(1, proof.size());
		assertEquals("CUSTOMER_SUPPLIED", proof.get(0).getStockOwnerType());
		assertEquals("CUST-1", proof.get(0).getStockOwnerCode());
		assertEquals("CUSTOMER-ORDER-1", proof.get(0).getSourceBusinessNo());
		assertEquals(8d, quantity(1));
	}

	@Test
	void exactOtherIssueRejectsDifferentOwner() {
		db.update("update stock set stock_owner_type='CONSIGNED',stock_owner_code='SUP-1' where id=1");
		var detail = command(2d, true).setBillType("other_out")
				.setStockOwnerType("CONSIGNED").setStockOwnerCode("SUP-2");

		assertThrows(CheckedException.class, () -> service.reduceStockWhenOut(detail));
		untouched();
	}

	@Test
	void exactStockIssueRejectsStockFromAnotherTenant() {
		db.update("update stock set tenant_id=2 where id=1");

		assertThrows(CheckedException.class, () -> service.reduceStockWhenOut(command(2d, true)));
		untouched();
	}

	@Test
	void fifoIssueCannotTakeStockFromAnotherFactory() {
		db.update("update stock set factory_code='F2' where id=1");

		assertThrows(CheckedException.class, () -> service.reduceStockWhenOut(command(2d, false)));
		untouched();
	}

	@Test
	void ordinaryFullDeductionDeletesOnlyOriginalStockAndClosesContainerUse() {
		var rows = service.reduceStockWhenOut(command(10d, false));

		assertEquals(1, rows.size());
		proof(rows.get(0), 1, 10);
		assertEquals(0, db.queryForObject("select count(*) from stock where id=1", Integer.class));
		assertEquals(99d, quantity(99));
		assertEquals(0, db.queryForObject("select is_used from container where code='C1'", Integer.class));
		assertNotNull(
				db.queryForObject("select end_time from container_history where code='C1'", java.sql.Timestamp.class));
	}

	@Test
	void ordinaryMultipleRowsKeepFifoOrderAndConserveRequestedQuantity() {
		db.update("update stock set mat_num=3 where id=1");
		addStock(2, 4);

		var rows = service.reduceStockWhenOut(command(5d, false));

		assertEquals(2, rows.size());
		proof(rows.get(0), 1, 3);
		proof(rows.get(1), 2, 2);
		assertEquals(2d, quantity(2));
		assertEquals(99d, quantity(99));
	}

	@Test
	void frozenPartialDeductionPreservesOriginalIdentityAndExactQuantity() {
		var rows = service.reduceStockWhenOut(command(2d, true));

		assertEquals(1, rows.size());
		proof(rows.get(0), 1, 2);
		assertEquals(8d, quantity(1));
		assertEquals(99d, quantity(99));
	}

	@Test
	void frozenFullDeductionDoesNotSwitchToOtherMatchingRows() {
		addStock(2, 4);

		var rows = service.reduceStockWhenOut(command(10d, true));

		assertEquals(1, rows.size());
		proof(rows.get(0), 1, 10);
		assertEquals(4d, quantity(2));
		assertEquals(99d, quantity(99));
		assertEquals(1, db.queryForObject("select is_used from container where code='C1'", Integer.class));
	}

	@Test
	void ordinaryZeroUpdateCannotReturnDeductionProof() {
		zeroUpdateId = 1;

		assertThrows(CheckedException.class, () -> service.reduceStockWhenOut(command(2d, false)));

		untouched();
	}

	@Test
	void ordinaryZeroDeleteCannotReturnDeductionProof() {
		zeroDeleteId = 1;

		assertThrows(CheckedException.class, () -> service.reduceStockWhenOut(command(10d, false)));

		untouched();
	}

	@Test
	void frozenZeroUpdateCannotReturnDeductionProof() {
		zeroUpdateId = 1;

		assertThrows(CheckedException.class, () -> service.reduceStockWhenOut(command(2d, true)));

		untouched();
	}

	@Test
	void frozenZeroDeleteCannotReturnDeductionProof() {
		zeroDeleteId = 1;

		assertThrows(CheckedException.class, () -> service.reduceStockWhenOut(command(10d, true)));

		untouched();
	}

	@Test
	void laterZeroUpdateRollsBackEarlierStockDeletion() {
		db.update("update stock set mat_num=3 where id=1");
		addStock(2, 4);
		zeroUpdateId = 2;

		assertThrows(CheckedException.class, () -> service.reduceStockWhenOut(command(5d, false)));

		assertEquals(3d, quantity(1));
		assertEquals(4d, quantity(2));
		assertEquals(99d, quantity(99));
	}

	@Test
	void laterZeroDeleteRollsBackEarlierStockDeletion() {
		db.update("update stock set mat_num=3 where id=1");
		addStock(2, 4);
		zeroDeleteId = 2;

		assertThrows(CheckedException.class, () -> service.reduceStockWhenOut(command(7d, false)));

		assertEquals(3d, quantity(1));
		assertEquals(4d, quantity(2));
		assertEquals(99d, quantity(99));
	}

	@Test
	void missingDetailIsRejectedBeforeAnyStockWrite() {
		assertThrows(CheckedException.class, () -> service.reduceStockWhenOut(null));

		untouched();
	}

	@Test
	void ordinaryInvalidRequestedQuantityCannotIncreaseOrInvalidateStock() {
		for (Double value : Arrays.asList(null, 0d, -1d, Double.NaN, Double.POSITIVE_INFINITY,
				Double.NEGATIVE_INFINITY)) {
			assertThrows(CheckedException.class, () -> service.reduceStockWhenOut(command(value, false)));
			untouched();
		}
	}

	@Test
	void frozenInvalidRequestedQuantityIsRejectedAsABusinessFailure() {
		for (Double value : Arrays.asList(null, 0d, -1d, Double.NaN, Double.POSITIVE_INFINITY,
				Double.NEGATIVE_INFINITY)) {
			assertThrows(CheckedException.class, () -> service.reduceStockWhenOut(command(value, true)));
			untouched();
		}
	}

	@Test
	void insufficientOrdinaryStockRejectsBeforeAnyDeduction() {
		assertThrows(CheckedException.class, () -> service.reduceStockWhenOut(command(11d, false)));

		untouched();
	}

	@Test
	void insufficientFrozenStockRejectsBeforeAnyDeduction() {
		assertThrows(CheckedException.class, () -> service.reduceStockWhenOut(command(11d, true)));

		untouched();
	}

	@Test
	void ordinaryAvailabilityLossDuringAllocationCannotReturnPartialProof() {
		when(production.activeReservedNum(1L)).thenReturn(BigDecimal.ZERO, BigDecimal.valueOf(8));

		assertThrows(CheckedException.class, () -> service.reduceStockWhenOut(command(4d, false)));

		untouched();
	}

	@Test
	void ordinaryDeductionCannotConsumeActiveReservedQuantity() {
		when(production.activeReservedNum(1L)).thenReturn(BigDecimal.valueOf(8));

		assertThrows(CheckedException.class, () -> service.reduceStockWhenOut(command(3d, false)));

		untouched();
	}

	@Test
	void frozenDeductionCannotConsumeActiveReservedQuantity() {
		when(production.activeReservedNum(1L)).thenReturn(BigDecimal.valueOf(8));

		assertThrows(CheckedException.class, () -> service.reduceStockWhenOut(command(3d, true)));

		untouched();
	}
}
