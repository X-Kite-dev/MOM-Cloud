package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.util.R;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.wms.dto.app.WmsOutBillDetailAppDTO;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.*;
import com.kitex.mom.wms.service.outBill.DefaultWmsOutBillService;
import com.kitex.mom.wms.service.outNotice.DefaultWmsOutNoticeService;
import com.kitex.mom.wms.controller.app.AppOutBillController;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * APP出库命令、通知记账、实际筛选条件与Spring事务回归。
 * <p>
 * 通过JDBC在H2上执行实际Mapper条件和更新，库存、拣货及配置作为适配边界。
 * <p>
 * 不覆盖真实PG、组织拦截器、完整预留或FIFO分配及实物拣货。
 */
class WmsMobileOutboundCommandTest {
	private JdbcTemplate db;
	private DefaultWmsOutBillService service;
	private boolean failStock, falsePicking, falsePickingDelete;
	private String sequenceData = "OUT-NEW";
	private String zeroWrite = "";
	private int detailInsertAttempts, zeroDetailInsertAt;
	private long nextBill = 300, nextDetail = 500;

	@BeforeEach
	void setup() {
		var ds = new DriverManagerDataSource("jdbc:h2:mem:mobile-out-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa",
				"");
		ds.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(ds);
		db.execute("create table notice(id bigint primary key,tenant_id bigint,"
				+ "factory_code varchar,warehouse_code varchar,warehouse_name "
				+ "varchar,bill_type varchar,state varchar,all_not_out_num double,"
				+ "all_out_num double,complete_time timestamp)");
		db.execute("create table notice_detail(id bigint primary key,notice_id "
				+ "bigint,tenant_id bigint,factory_code varchar,warehouse_code "
				+ "varchar,mat_code varchar,mat_unit varchar,source_stock_id bigint,"
				+ "notice_out_num double,not_out_num double,out_num double)");
		db.execute("create table bill(id bigint primary key,bill_no varchar,"
				+ "notice_id bigint,tenant_id bigint,factory_code varchar,"
				+ "warehouse_code varchar,warehouse_name varchar,bill_type varchar,"
				+ "state varchar,source varchar,create_time timestamp default "
				+ "current_timestamp,detail_finish_num double,detail_not_finish_num "
				+ "double)");
		db.execute("create table bill_detail(id bigint primary key,bill_id bigint,"
				+ "notice_detail_id bigint,tenant_id bigint,factory_code varchar,"
				+ "warehouse_code varchar,mat_code varchar,mat_batch varchar,"
				+ "mat_unit varchar,site_code varchar,container_code varchar,"
				+ "supplier_code varchar,source_stock_id bigint,mat_num double,"
				+ "operate_num double,operate_unit varchar)");
		db.execute("create table stock(id bigint primary key,quantity double)");
		db.execute("create table picking(bill_no varchar,quantity double)");
		db.update("insert into notice values(1,1,'F1','W1','Warehouse','other_retail','1',10,0,null)");
		db.update("insert into notice_detail values(2,1,1,'F1','W1','M1','KG',null,10,10,0)");
		db.update("insert into bill(id,bill_no,notice_id,tenant_id,factory_code,"
				+ "warehouse_code,warehouse_name,bill_type,state,source,"
				+ "detail_finish_num,detail_not_finish_num) values(200,'OUT-1',1,1,"
				+ "'F1','W1','Warehouse','other_retail','11','app',0,0)");
		db.update("insert into stock values(3,10)");
		for (Class<?> type : List.of(WmsOutNoticeEntity.class, WmsOutNoticeDetailEntity.class,
				WmsOutBillEntity.class, WmsOutBillDetailEntity.class)) {
			TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "mobile-out-command"),
					type);
		}

		var notices = mock(WmsOutNoticeMapper.class);
		when(notices.selectById(any(java.io.Serializable.class))).thenAnswer(call -> first(
				db.query("select * from notice where id=?", noticeMapper(), new Object[] { call.getArgument(0) })));
		when(notices.update(any(Wrapper.class))).thenCallRealMethod();
		when(notices.update(isNull(), any(Wrapper.class))).thenAnswer(call -> update("notice", call.getArgument(1)));
		var noticeDetails = mock(WmsOutNoticeDetailMapper.class);
		when(noticeDetails.selectList(any(Wrapper.class)))
				.thenAnswer(call -> query("notice_detail", call.getArgument(0), noticeDetailMapper()));
		when(noticeDetails.update(any(Wrapper.class))).thenCallRealMethod();
		when(noticeDetails.update(isNull(), any(Wrapper.class)))
				.thenAnswer(call -> update("notice_detail", call.getArgument(1)));
		var bills = mock(WmsOutBillMapper.class);
		when(bills.selectList(any(Wrapper.class))).thenAnswer(call -> query("bill", call.getArgument(0), billMapper()));
		when(bills.selectById(any(java.io.Serializable.class))).thenAnswer(call -> first(
				db.query("select * from bill where id=?", billMapper(), new Object[] { call.getArgument(0) })));
		when(bills.update(any(Wrapper.class))).thenCallRealMethod();
		when(bills.update(isNull(), any(Wrapper.class))).thenAnswer(call -> update("bill", call.getArgument(1)));
		when(bills.insert(any(WmsOutBillEntity.class))).thenAnswer(call -> {
			if (zeroWrite.equals("bill_insert")) {
				return 0;
			}
			WmsOutBillEntity b = call.getArgument(0);
			b.setId(nextBill++);
			return db.update("insert into bill(id,bill_no,notice_id,tenant_id,factory_code,"
					+ "warehouse_code,warehouse_name,bill_type,state,source,"
					+ "detail_finish_num,detail_not_finish_num) values(?,?,?,?,?,?,?,?,"
					+ "?,?,?,?)",
					b.getId(), b.getBillNo(), b.getNoticeId(), b.getTenantId(), b.getFactoryCode(),
					b.getWarehouseCode(), b.getWarehouseName(), b.getBillType(), b.getState(), b.getSource(),
					b.getDetailFinishNum(), b.getDetailNotFinishNum());
		});
		var billDetails = mock(WmsOutBillDetailMapper.class);
		when(billDetails.selectList(any(Wrapper.class)))
				.thenAnswer(call -> query("bill_detail", call.getArgument(0), billDetailMapper()));
		when(billDetails.insert(any(WmsOutBillDetailEntity.class)))
				.thenAnswer(call -> insertDetail(call.getArgument(0)));
		when(billDetails.insert(anyCollection())).thenAnswer(call -> {
			Collection<WmsOutBillDetailEntity> rows = call.getArgument(0);
			rows.forEach(this::insertDetail);
			return null;
		});
		when(billDetails.updateById(any(WmsOutBillDetailEntity.class))).thenAnswer(call -> {
			if (zeroWrite.equals("bill_detail_merge")) {
				return 0;
			}
			WmsOutBillDetailEntity row = call.getArgument(0);
			return db.update("update bill_detail set mat_num=?,operate_num=? where id=?", row.getMatNum(),
					row.getOperateNum(), row.getId());
		});
		var stocks = mock(WmsLiveStockService.class);
		when(stocks.reduceStockWhenOut(any())).thenAnswer(call -> {
			WmsOutBillDetailEntity row = call.getArgument(0);
			db.update("update stock set quantity=quantity-? where id=3", row.getMatNum());
			if (failStock) {
				throw new IllegalStateException("stock boundary failed after write");
			}
			var proof = new WmsLiveStockEntity().setMatCode(row.getMatCode()).setMatNum(row.getMatNum());
			proof.setId(3L);
			return List.of(proof);
		});
		var picking = mock(WmsPickingAreaService.class);
		when(picking.insertByStockList(anyList(), any(WmsOutBillEntity.class), anyLong())).thenAnswer(call -> {
			List<WmsLiveStockEntity> rows = call.getArgument(0);
			WmsOutBillEntity row = call.getArgument(1);
			db.update("insert into picking values(?,?)", row.getBillNo(), rows.get(0).getMatNum());
			return !falsePicking;
		});
		when(picking.deleteByOutBill(any())).thenAnswer(call -> {
			WmsOutBillEntity row = call.getArgument(0);
			db.update("delete from picking where bill_no=?", row.getBillNo());
			return !falsePickingDelete;
		});
		var sequence = mock(RemoteSeqConfigService.class);
		when(sequence.create("wms_out_bill")).thenAnswer(call -> R.ok(sequenceData, null));
		var warehouse = mock(WmsWarehouseBusinessGuard.class);
		when(warehouse.check(eq("W1"), anyString(), eq(false))).thenReturn(true);
		var target = new DefaultWmsOutBillService();
		ReflectionTestUtils.setField(target, "wmsOutNoticeMapper", notices);
		ReflectionTestUtils.setField(target, "wmsOutNoticeDetailMapper", noticeDetails);
		ReflectionTestUtils.setField(target, "wmsOutBillMapper", bills);
		ReflectionTestUtils.setField(target, "wmsOutBillDetailMapper", billDetails);
		ReflectionTestUtils.setField(target, "wmsLiveStockService", stocks);
		ReflectionTestUtils.setField(target, "wmsPickingAreaService", picking);
		ReflectionTestUtils.setField(target, "warehouseBusinessGuard", warehouse);
		ReflectionTestUtils.setField(target, "remoteSeqConfigService", sequence);
		ReflectionTestUtils.setField(target, "materialReservationService", mock(WmsMaterialReservationService.class));
		ReflectionTestUtils.setField(target, "salesStockReservationService",
				mock(WmsSalesStockReservationService.class));
		var proxy = new ProxyFactory(target);
		proxy.setProxyTargetClass(true);
		proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),
				new AnnotationTransactionAttributeSource()));
		service = (DefaultWmsOutBillService) proxy.getProxy();
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
	}

	@AfterEach
	void clear() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	private WmsOutBillDetailAppDTO command() {
		return new WmsOutBillDetailAppDTO().setNoticeId(1L).setBillNo("OUT-1").setMatCode("M1")
				.setMatBatch("B1").setSiteCode("S1").setSupplierCode("SUP1").setContainerCode("C1").setMatNum(2d);
	}

	private int insertDetail(WmsOutBillDetailEntity r) {
		if (++detailInsertAttempts == zeroDetailInsertAt) {
			return 0;
		}
		r.setId(nextDetail++);
		return db.update("insert into bill_detail values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", r.getId(), r.getBillId(),
				r.getNoticeDetailId(),
				r.getTenantId(), r.getFactoryCode(), r.getWarehouseCode(), r.getMatCode(), r.getMatBatch(),
				r.getMatUnit(), r.getSiteCode(),
				r.getContainerCode(), r.getSupplierCode(), r.getSourceStockId(), r.getMatNum(), r.getOperateNum(),
				r.getOperateUnit());
	}

	private double number(String table, String column) {
		return db.queryForObject("select " + column + " from " + table, Double.class);
	}

	private int count(String table) {
		return db.queryForObject("select count(*) from " + table, Integer.class);
	}

	private void untouched() {
		assertEquals(10d, number("stock", "quantity"));
		assertEquals(10d, number("notice", "all_not_out_num"));
		assertEquals(10d, number("notice_detail", "not_out_num"));
		assertEquals(0, count("bill_detail"));
		assertEquals(0, count("picking"));
	}

	private static <T> T first(List<T> rows) {
		return rows.isEmpty() ? null : rows.get(0);
	}

	private record Bound(String text, Object[] values) {
	}

	private Bound bind(String sql, Wrapper<?> wrapper) {
		var matcher = Pattern.compile("#\\{ew\\.paramNameValuePairs\\.(\\w+)(?:,[^}]*)?}").matcher(sql);
		List<Object> values = new ArrayList<>();
		StringBuilder result = new StringBuilder();
		Map<String, Object> parameters = ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper)
				.getParamNameValuePairs();
		while (matcher.find()) {
			values.add(parameters.get(matcher.group(1)));
			matcher.appendReplacement(result, "?");
		}
		matcher.appendTail(result);
		return new Bound(result.toString(), values.toArray());
	}

	private <T> List<T> query(String table, Wrapper<?> wrapper, RowMapper<T> mapper) {
		var sql = bind("select * from " + table + " " + wrapper.getCustomSqlSegment(), wrapper);
		return db.query(sql.text(), mapper, sql.values());
	}

	private int update(String table, Wrapper<?> wrapper) {
		var change = (LambdaUpdateWrapper<?>) wrapper;
		String operation = switch (table) {
		case "notice" -> change.getSqlSet().contains("all_not_out_num") ? "notice_quantity" : "notice_complete";
		case "notice_detail" -> "notice_detail_quantity";
		case "bill" -> change.getSqlSet().contains("detail_not_finish_num") ? "bill_detail_count" : "bill_complete";
		default -> table;
		};
		if (zeroWrite.equals(operation)) {
			return 0;
		}
		var sql = bind("update " + table + " set " + change.getSqlSet() + " " + change.getCustomSqlSegment(), wrapper);
		return db.update(sql.text(), sql.values());
	}

	private RowMapper<WmsOutNoticeEntity> noticeMapper() {
		return (r, n) -> {
			var b = new WmsOutNoticeEntity();
			b.setId(r.getLong("id"));
			b.setTenantId(r.getLong("tenant_id"));
			b.setFactoryCode(r.getString("factory_code"));
			b.setWarehouseCode(r.getString("warehouse_code"));
			b.setWarehouseName(r.getString("warehouse_name"));
			b.setBillType(r.getString("bill_type"));
			b.setState(r.getString("state"));
			b.setAllNotOutNum((Double) r.getObject("all_not_out_num"));
			b.setAllOutNum((Double) r.getObject("all_out_num"));
			return b;
		};
	}

	private RowMapper<WmsOutNoticeDetailEntity> noticeDetailMapper() {
		return (r, n) -> {
			var b = new WmsOutNoticeDetailEntity();
			b.setId(r.getLong("id"));
			b.setNoticeId(r.getLong("notice_id"));
			b.setTenantId(r.getLong("tenant_id"));
			b.setFactoryCode(r.getString("factory_code"));
			b.setWarehouseCode(r.getString("warehouse_code"));
			b.setMatCode(r.getString("mat_code"));
			b.setMatUnit(r.getString("mat_unit"));
			b.setSourceStockId((Long) r.getObject("source_stock_id"));
			b.setNoticeOutNum((Double) r.getObject("notice_out_num"));
			b.setNotOutNum((Double) r.getObject("not_out_num"));
			b.setOutNum((Double) r.getObject("out_num"));
			return b;
		};
	}

	private RowMapper<WmsOutBillEntity> billMapper() {
		return (r, n) -> {
			var b = new WmsOutBillEntity();
			b.setId(r.getLong("id"));
			b.setTenantId(r.getLong("tenant_id"));
			b.setBillNo(r.getString("bill_no"));
			b.setNoticeId((Long) r.getObject("notice_id"));
			b.setFactoryCode(r.getString("factory_code"));
			b.setWarehouseCode(r.getString("warehouse_code"));
			b.setWarehouseName(r.getString("warehouse_name"));
			b.setBillType(r.getString("bill_type"));
			b.setState(r.getString("state"));
			b.setSource(r.getString("source"));
			b.setDetailFinishNum((Double) r.getObject("detail_finish_num"));
			b.setDetailNotFinishNum((Double) r.getObject("detail_not_finish_num"));
			return b;
		};
	}

	private RowMapper<WmsOutBillDetailEntity> billDetailMapper() {
		return (r, n) -> {
			var b = new WmsOutBillDetailEntity();
			b.setId(r.getLong("id"));
			b.setTenantId(r.getLong("tenant_id"));
			b.setBillId(r.getLong("bill_id"));
			b.setNoticeDetailId(r.getLong("notice_detail_id"));
			b.setFactoryCode(r.getString("factory_code"));
			b.setWarehouseCode(r.getString("warehouse_code"));
			b.setMatCode(r.getString("mat_code"));
			b.setMatBatch(r.getString("mat_batch"));
			b.setSiteCode(r.getString("site_code"));
			b.setContainerCode(r.getString("container_code"));
			b.setSupplierCode(r.getString("supplier_code"));
			b.setMatUnit(r.getString("mat_unit"));
			b.setMatNum((Double) r.getObject("mat_num"));
			b.setOperateNum((Double) r.getObject("operate_num"));
			b.setOperateUnit(r.getString("operate_unit"));
			b.setSourceStockId((Long) r.getObject("source_stock_id"));
			return b;
		};
	}

	@Test
	void validOriginalAppBillUpdatesItsNoticeAndStockInOneTransaction() {
		assertEquals("OUT-1", service.setOutBillNum(command()));

		assertEquals(8d, number("stock", "quantity"));
		assertEquals(8d, number("notice", "all_not_out_num"));
		assertEquals(8d, number("notice_detail", "not_out_num"));
		assertEquals(2d, number("bill_detail", "mat_num"));
		assertEquals(1, count("picking"));

		assertEquals("OUT-1", service.setOutBillNum(command().setMatNum(3d)));

		assertEquals(5d, number("stock", "quantity"));
		assertEquals(5d, number("notice", "all_not_out_num"));
		assertEquals(5d, number("bill_detail", "mat_num"));
		assertEquals(5d, number("bill_detail", "operate_num"));
		assertEquals(2, count("picking"));
	}

	@Test
	void fractionalScansAccumulateExactBillQuantities() {
		service.setOutBillNum(command().setMatNum(0.1D));
		service.setOutBillNum(command().setMatNum(0.2D));

		assertEquals(0.3D, number("bill_detail", "mat_num"));
		assertEquals(0.3D, number("bill_detail", "operate_num"));
		assertEquals(9.7D, number("notice", "all_not_out_num"));
	}

	@Test
	void newRequestWithoutBillNumberCanCreateNormalAppBill() {
		db.update("delete from bill");
		var c = command().setBillNo(null);

		assertEquals("OUT-NEW", service.setOutBillNum(c));

		assertEquals(1, count("bill"));
		assertEquals(8d, number("stock", "quantity"));
		assertEquals(8d, number("notice", "all_not_out_num"));
	}

	@Test
	void explicitUnknownBillCannotCreateAReplacement() {
		assertThrows(CheckedException.class, () -> service.setOutBillNum(command().setBillNo("MISSING")));

		untouched();
		assertEquals(1, count("bill"));
	}

	@Test
	void explicitBillCannotAppendUnderADifferentOrMissingSourceNotice() {
		db.update("update bill set notice_id=null where id=200");

		assertThrows(CheckedException.class, () -> service.setOutBillNum(command()));

		untouched();
	}

	@Test
	void completedAppBillCannotBeReopenedWhileNoticeStillRuns() {
		db.update("update bill set state='12' where id=200");

		assertThrows(CheckedException.class, () -> service.setOutBillNum(command()));

		untouched();
	}

	@Test
	void existingBillMustMatchNoticeOrganizationWarehouseAndBusiness() {
		for (String mutation : List.of("factory_code='F2'", "tenant_id=2", "warehouse_code='W2'",
				"bill_type='other_pms'")) {
			db.update("update bill set " + mutation + " where id=200");
			assertThrows(CheckedException.class, () -> service.setOutBillNum(command()), mutation);
			untouched();
			db.update("update bill set factory_code='F1',tenant_id=1,"
					+ "warehouse_code='W1',bill_type='other_retail' where id=200");
		}
	}

	@Test
	void noticeAndDetailMustBelongToActualOrganizationAndOriginalWarehouse() {
		for (String mutation : List.of("notice set factory_code='F2'", "notice set tenant_id=2",
				"notice_detail set factory_code='F2'", "notice_detail set tenant_id=2",
				"notice_detail set warehouse_code='W2'")) {
			db.update("update " + mutation);
			assertThrows(CheckedException.class, () -> service.setOutBillNum(command()), mutation);
			untouched();
			db.update("update notice set factory_code='F1',tenant_id=1");
			db.update("update notice_detail set factory_code='F1',tenant_id=1,warehouse_code='W1'");
		}
	}

	@Test
	void standardAndOperateQuantitiesMustBePositiveAndFinite() {
		for (double value : new double[] { 0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY }) {
			assertThrows(CheckedException.class, () -> service.setOutBillNum(command().setMatNum(value)));
			untouched();
			assertThrows(CheckedException.class, () -> service.setOutBillNum(command().setOperateNum(value)));
			untouched();
		}
	}

	@Test
	void missingActualContextCannotBeReplacedByReadBackOrganization() {
		FactoryContextHolder.clear();
		TenantContextHolder.clear();

		assertThrows(CheckedException.class, () -> service.setOutBillNum(command()));

		untouched();
	}

	@Test
	void multipleRunningAppBillsRequireExplicitOriginalBillInsteadOfFirstRow() {
		db.update("insert into bill(id,bill_no,notice_id,tenant_id,factory_code,"
				+ "warehouse_code,bill_type,state,source,detail_finish_num,"
				+ "detail_not_finish_num) values(201,'OUT-2',1,1,'F1','W1',"
				+ "'other_retail','11','app',0,0)");

		assertThrows(CheckedException.class, () -> service.setOutBillNum(command().setBillNo(null)));

		untouched();
	}

	@Test
	void stockExceptionRollsBackOriginalBillAndNoticeWrites() {
		failStock = true;

		assertThrows(IllegalStateException.class, () -> service.setOutBillNum(command()));

		untouched();
	}

	@Test
	void falsePickingReceiptRollsBackStockAndOriginalDocuments() {
		falsePicking = true;

		assertThrows(CheckedException.class, () -> service.setOutBillNum(command()));

		untouched();
	}

	@Test
	void falsePickingDeletionCannotFinishNotice() {
		db.update("update notice set all_not_out_num=2");
		db.update("update notice_detail set not_out_num=2");
		falsePickingDelete = true;

		assertThrows(CheckedException.class, () -> service.setOutBillNum(command()));

		assertEquals(10d, number("stock", "quantity"));
		assertEquals(2d, number("notice", "all_not_out_num"));
		assertEquals("1", db.queryForObject("select state from notice", String.class));
		assertEquals("11", db.queryForObject("select state from bill", String.class));
		assertEquals(0, count("picking"));
	}

	@Test
	void duplicateMatchingBillDetailsCannotBeMergedByGuessingFirstRow() {
		for (int i = 0; i < 2; i++) {
			var row = new WmsOutBillDetailEntity().setBillId(200L).setNoticeDetailId(2L).setFactoryCode("F1")
					.setWarehouseCode("W1")
					.setMatCode("M1").setMatBatch("B1").setSiteCode("S1").setContainerCode("C1").setSupplierCode("SUP1")
					.setMatUnit("KG").setMatNum(1d).setOperateNum(1d);
			row.setTenantId(1L);
			insertDetail(row);
		}

		assertThrows(CheckedException.class, () -> service.setOutBillNum(command()));

		assertEquals(10d, number("stock", "quantity"));
		assertEquals(10d, number("notice", "all_not_out_num"));
		assertEquals(2d, db.queryForObject("select sum(mat_num) from bill_detail", Double.class));
		assertEquals(0, count("picking"));
	}

	@Test
	void controllerMustReturnTheOriginalBillNumberAsDataInsteadOfOnlyMessage() {
		var writes = mock(DefaultWmsOutBillService.class);
		when(writes.setOutBillNum(any())).thenReturn("OUT-1");
		var controller = new AppOutBillController(mock(DefaultWmsOutNoticeService.class), writes);
		var response = controller.setInBillNum(command());

		assertEquals(0, response.getCode());
		assertEquals("OUT-1", response.getData());
	}

	@Test
	void missingSequenceDataCannotConsumeStockOrCreateAnUnnumberedBill() {
		db.update("delete from bill");

		for (String value : Arrays.asList(null, "", "  ")) {
			sequenceData = value;
			assertThrows(CheckedException.class, () -> service.setOutBillNum(command().setBillNo(null)));
			untouched();
			assertEquals(0, count("bill"));
		}
	}

	@Test
	void continuingAnInventoryBillCannotBypassTheUnifiedAdjustmentContext() {
		db.update("update notice set bill_type='other_inventory'");
		db.update("update bill set bill_type='other_inventory'");

		assertThrows(CheckedException.class, () -> service.setOutBillNum(command()));

		untouched();
	}

	@Test
	void legacyOptionalOperateTotalDoesNotBlockValidStandardQuantityContinuation() {
		var row = new WmsOutBillDetailEntity().setBillId(200L).setNoticeDetailId(2L).setFactoryCode("F1")
				.setWarehouseCode("W1")
				.setMatCode("M1").setMatBatch("B1").setSiteCode("S1").setContainerCode("C1").setSupplierCode("SUP1")
				.setMatUnit("KG").setMatNum(1d).setOperateNum(null);
		row.setTenantId(1L);
		insertDetail(row);
		db.update("update stock set quantity=9");
		db.update("update notice set all_not_out_num=9,all_out_num=1");
		db.update("update notice_detail set not_out_num=9,out_num=1");

		assertEquals("OUT-1", service.setOutBillNum(command()));

		assertEquals(7d, number("stock", "quantity"));
		assertEquals(7d, number("notice", "all_not_out_num"));
		assertEquals(3d, number("bill_detail", "mat_num"));
		assertEquals(2d, number("bill_detail", "operate_num"));
	}

	@Test
	void finiteIncomingQuantityCannotOverflowTheOriginalNoticeCounter() {
		db.update("update notice set all_not_out_num=?,all_out_num=?", Double.MAX_VALUE, Double.MAX_VALUE);
		db.update("update notice_detail set not_out_num=?", Double.MAX_VALUE);
		db.update("update stock set quantity=?", Double.MAX_VALUE);

		assertThrows(CheckedException.class, () -> service.setOutBillNum(command().setMatNum(Double.MAX_VALUE)));

		assertEquals(Double.MAX_VALUE, number("stock", "quantity"));
		assertEquals(Double.MAX_VALUE, number("notice", "all_out_num"));
		assertEquals(0, count("bill_detail"));
		assertEquals(0, count("picking"));
	}

	@Test
	void newBillHeaderZeroRowsCannotReturnAReceipt() {
		db.update("delete from bill");
		zeroWrite = "bill_insert";

		assertThrows(CheckedException.class, () -> service.setOutBillNum(command().setBillNo(null)));

		untouched();
		assertEquals(0, count("bill"));
	}

	@Test
	void newBillDetailZeroRowsRollsBackItsInsertedHeader() {
		db.update("delete from bill");
		zeroDetailInsertAt = 1;

		assertThrows(CheckedException.class, () -> service.setOutBillNum(command().setBillNo(null)));

		untouched();
		assertEquals(0, count("bill"));
	}

	@Test
	void appendedDetailZeroRowsCannotConsumeStock() {
		zeroDetailInsertAt = 1;

		assertThrows(CheckedException.class, () -> service.setOutBillNum(command()));

		untouched();
		assertEquals(0d, number("bill", "detail_not_finish_num"));
	}

	@Test
	void appendedDetailCounterZeroRowsRollsBackTheDetail() {
		zeroWrite = "bill_detail_count";

		assertThrows(CheckedException.class, () -> service.setOutBillNum(command()));

		untouched();
		assertEquals(0d, number("bill", "detail_not_finish_num"));
	}

	@Test
	void mergedDetailZeroRowsLeavesPreviouslyCommittedReceiptUnchanged() {
		assertEquals("OUT-1", service.setOutBillNum(command()));

		zeroWrite = "bill_detail_merge";

		assertThrows(CheckedException.class, () -> service.setOutBillNum(command().setMatNum(3d)));

		assertEquals(8d, number("stock", "quantity"));
		assertEquals(8d, number("notice", "all_not_out_num"));
		assertEquals(8d, number("notice_detail", "not_out_num"));
		assertEquals(2d, number("bill_detail", "mat_num"));
		assertEquals(2d, number("bill_detail", "operate_num"));
		assertEquals(1, count("picking"));
	}

	@Test
	void noticeDetailQuantityZeroRowsRollsBackTheBillDetail() {
		zeroWrite = "notice_detail_quantity";

		assertThrows(CheckedException.class, () -> service.setOutBillNum(command()));

		untouched();
	}

	@Test
	void noticeTotalZeroRowsRollsBackItsDetailAccounting() {
		zeroWrite = "notice_quantity";

		assertThrows(CheckedException.class, () -> service.setOutBillNum(command()));

		untouched();
		assertEquals(0d, number("notice_detail", "out_num"));
	}

	private void completingNoticeUnchanged() {
		assertEquals(10d, number("stock", "quantity"));
		assertEquals(2d, number("notice", "all_not_out_num"));
		assertEquals(2d, number("notice_detail", "not_out_num"));
		assertEquals(0d, number("notice_detail", "out_num"));
		assertEquals("1", db.queryForObject("select state from notice", String.class));
		assertEquals("11", db.queryForObject("select state from bill", String.class));
		assertEquals(0, count("bill_detail"));
		assertEquals(0, count("picking"));
	}

	@Test
	void billCompletionZeroRowsRollsBackStockAndNoticeAccounting() {
		db.update("update notice set all_not_out_num=2");
		db.update("update notice_detail set not_out_num=2");
		zeroWrite = "bill_complete";

		assertThrows(CheckedException.class, () -> service.setOutBillNum(command()));

		completingNoticeUnchanged();
	}

	@Test
	void noticeCompletionZeroRowsRollsBackBillStockAndPickingDeletion() {
		db.update("update notice set all_not_out_num=2");
		db.update("update notice_detail set not_out_num=2");
		zeroWrite = "notice_complete";

		assertThrows(CheckedException.class, () -> service.setOutBillNum(command()));

		completingNoticeUnchanged();
	}

	@Test
	void finalSuccessfulReceiptCompletesOriginalBillAndNoticeAfterPickingClears() {
		db.update("update notice set all_not_out_num=2");
		db.update("update notice_detail set not_out_num=2");

		assertEquals("OUT-1", service.setOutBillNum(command()));

		assertEquals(8d, number("stock", "quantity"));
		assertEquals(0d, number("notice", "all_not_out_num"));
		assertEquals(0d, number("notice_detail", "not_out_num"));
		assertEquals(2d, number("notice_detail", "out_num"));
		assertEquals(2d, number("bill_detail", "mat_num"));
		assertEquals(0, count("picking"));
		assertEquals("12", db.queryForObject("select state from bill", String.class));
		assertEquals("2", db.queryForObject("select state from notice", String.class));
		assertNotNull(db.queryForObject("select complete_time from notice", java.sql.Timestamp.class));
	}

	@Test
	void sharedSaveWithSecondDetailZeroRowsRollsBackHeaderAndFirstDetail() {
		db.update("delete from bill");
		zeroDetailInsertAt = 2;
		var bill = new WmsOutBillEntity();
		bill.setBillNo("PC-1");
		bill.setBillType("other_retail");
		bill.setFactoryCode("F1");
		bill.setWarehouseCode("W1");
		bill.setWarehouseName("Warehouse");
		bill.setDetails(List.of(new WmsOutBillDetailEntity().setMatCode("M1").setMatUnit("EA").setMatNum(2d),
				new WmsOutBillDetailEntity().setMatCode("M2").setMatUnit("EA").setMatNum(3d)));
		bill.setTenantId(1L);

		assertThrows(CheckedException.class, () -> service.saveDeep(bill));

		untouched();
		assertEquals(0, count("bill"));
	}

	@Test
	void sharedSavePersistsEveryDetailWithOriginalHeaderAssociation() {
		db.update("delete from bill");
		var bill = new WmsOutBillEntity();
		bill.setBillNo("PC-1");
		bill.setBillType("other_retail");
		bill.setFactoryCode("F1");
		bill.setWarehouseCode("W1");
		bill.setWarehouseName("Warehouse");
		bill.setDetails(List.of(new WmsOutBillDetailEntity().setMatCode("M1").setMatUnit("EA").setMatNum(2d),
				new WmsOutBillDetailEntity().setMatCode("M2").setMatUnit("EA").setMatNum(3d)));
		bill.setTenantId(1L);

		assertTrue(service.saveDeep(bill));

		assertEquals(1, count("bill"));
		assertEquals(2, count("bill_detail"));
		assertEquals(5d, db.queryForObject("select sum(mat_num) from bill_detail", Double.class));
		assertEquals(2, db.queryForObject("select count(*) from bill_detail where bill_id=? and "
				+ "factory_code='F1' and warehouse_code='W1'", Integer.class, bill.getId()));
		assertEquals(10d, number("stock", "quantity"));
	}
}
