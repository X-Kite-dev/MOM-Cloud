package com.kitex.mom.wms.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.kitex.mom.mes.dto.MesMaterialReturnReceiptEvent;
import com.kitex.mom.wms.dto.WmsProductionReturnReceiptCommand;
import com.kitex.mom.wms.dto.app.WmsReturnFinalizeDTO.Command;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.WmsIdentifierPolicyService;
import com.kitex.mom.wms.service.WmsReturnFinalizeService;
import com.kitex.mom.wms.service.WmsReturnReceiptAccessService;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import com.kitex.mom.wms.enums.menu.ReturnReceiptActionEnum;
import com.kitex.common.security.component.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import com.kitex.mom.wms.service.inBill.service.WmsProductionReturnInBillService;
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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 真实退料终结、入库、预留回补及Outbox生成，共享同一本地事务。 Mapper边界执行真实wrapper谓词；不启动PG、组织拦截器、远程MES或容器业务。
 */
class WmsProductionReturnClosureTest {
	private JdbcTemplate db;
	private WmsProductionReturnInBillService inbound;
	private WmsProductionReturnReceiptService service;
	private WmsReturnFinalizeService finalization;
	private WmsWarehouseBusinessGuard mobileGuard;
	private WmsMaterialReservationServiceImpl reservations;
	private String zeroWrite = "";
	private long failDetailId = -1;
	private final AtomicLong ids = new AtomicLong(1000);
	private final LocalDateTime time = LocalDateTime.of(2026, 10, 23, 10, 0);

	@BeforeEach
	void setup() throws Exception {
		var source = new DriverManagerDataSource("jdbc:h2:mem:return-closure-" + UUID.randomUUID()
				+ ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(source);
		db.execute("CREATE SCHEMA mom_wms");
		String migration = Files.readString(Path.of("../database/wms-return-finalize-receipt-pg.sql"));
		int start = migration.indexOf("CREATE TABLE");
		db.execute(migration.substring(start, migration.indexOf(';', start)));
		db.execute("create table reservation(id bigint primary key,reservation_no varchar,status varchar)");
		db.execute("create table reservation_detail(id bigint primary key,"
				+ "reservation_id bigint,live_stock_id bigint,"
				+ "actual_mat_code varchar,requirement_mat_code varchar,reserved_num decimal(18,8),"
				+ "consumed_num decimal(18,8),returned_num decimal(18,8),released_num decimal(18,8),"
				+ "substitute_ratio decimal(18,8),status varchar,parent_detail_id bigint,return_bill_detail_id bigint)");
		db.execute("create table stock(id bigint primary key,mat_num double,source_in_bill_detail_id bigint)");
		db.execute("create table flow(id bigint primary key,mat_num double,"
				+ "bill_detail_id bigint,source_stock_id bigint)");
		db.execute("create table notice(id bigint primary key,factory_code varchar,state varchar,"
				+ "return_receipt_status varchar,notice_in_num double,return_overage_num decimal(18,8))");
		db.execute("create table bill(id bigint primary key,notice_id bigint,state "
				+ "varchar,in_time timestamp,bill_no varchar,"
				+ "detail_finish_num int,detail_not_finish_num int)");
		db.execute("create table bill_detail(id bigint primary key,bill_id bigint,"
				+ "mat_num double,in_time timestamp)");
		db.execute("create table outbox(id bigint generated by default as identity "
				+ "primary key,event_id varchar unique,"
				+ "notice_id bigint,fingerprint varchar,payload_json clob,status varchar)");
		db.execute("create table operation(id bigint generated by default as "
				+ "identity primary key,request_id varchar,"
				+ "notice_id bigint,operation_type varchar,tenant_id bigint default 1)");
		db.update("insert into reservation values(10,'RES-10','CONSUMED')");
		db.update("insert into reservation_detail values(101,10,1,'M1','M1',10,10,0,0,1,'CLOSED',null,null)");
		db.update("insert into notice values(20,'F1','1','WAITING',10,0)");
		db.execute("alter table notice add tenant_id bigint default 1");
		for (String column : List.of("return_shortage_num decimal(18,8)", "return_reporter_code varchar",
				"return_disposition varchar", "return_disposition_reason varchar", "return_reviewer_code varchar")) {
			db.execute("alter table notice add " + column);
		}
		addBill(30, 301, 4);
		addBill(31, 311, 6);
		for (Class<?> type : List.of(WmsMaterialReservationEntity.class, WmsMaterialReservationDetailEntity.class,
				WmsInNoticeEntity.class, WmsInBillEntity.class, WmsInBillDetailEntity.class,
				WmsMesMaterialReturnReceiptOutboxEntity.class, WmsProductionReturnReceiptOperationEntity.class)) {
			TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "return-closure"),
					type);
		}

		var headers = mock(WmsMaterialReservationMapper.class);
		when(headers.selectOne(any(Wrapper.class), eq(false)))
				.thenAnswer(c -> first(query("reservation", c.getArgument(0), (r, n) -> {
					var row = new WmsMaterialReservationEntity();
					row.setId(r.getLong("id"));
					row.setReservationNo(r.getString("reservation_no"));
					row.setStatus(r.getString("status"));
					return row;
				})));
		when(headers.update(isNull(), any(Wrapper.class)))
				.thenAnswer(c -> zeroWrite.equals("reservation") ? 0 : update("reservation", c.getArgument(1)));
		var details = mock(WmsMaterialReservationDetailMapper.class);
		when(details.selectList(any(Wrapper.class)))
				.thenAnswer(c -> query("reservation_detail", c.getArgument(0), detailRow()));
		when(details.update(isNull(), any(Wrapper.class)))
				.thenAnswer(c -> zeroWrite.equals("returned") ? 0 : update("reservation_detail", c.getArgument(1)));
		when(details.insert(any(WmsMaterialReservationDetailEntity.class))).thenAnswer(c -> {
			if (zeroWrite.equals("replenish")) {
				return 0;
			}
			WmsMaterialReservationDetailEntity row = c.getArgument(0);
			row.setId(ids.incrementAndGet());
			return db.update("insert into reservation_detail values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
					row.getId(), row.getReservationId(), row.getLiveStockId(), row.getActualMatCode(),
					row.getRequirementMatCode(), row.getReservedNum(), row.getConsumedNum(), row.getReturnedNum(),
					row.getReleasedNum(), row.getSubstituteRatio(), row.getStatus(), row.getParentDetailId(),
					row.getReturnBillDetailId());
		});
		reservations = proxy(new WmsMaterialReservationServiceImpl(headers, details, null, null,
				null, null, null, null, null, null, null, null, null), source);
		var stocks = mock(WmsLiveStockMapper.class);
		when(stocks.insert(any(WmsLiveStockEntity.class))).thenAnswer(c -> {
			WmsLiveStockEntity row = c.getArgument(0);
			if (zeroWrite.equals("stock") || Objects.equals(row.getSourceInBillDetailId(), failDetailId)) {
				return 0;
			}
			row.setId(ids.incrementAndGet());
			return db.update("insert into stock values(?,?,?)", row.getId(), row.getMatNum(),
					row.getSourceInBillDetailId());
		});
		var flows = mock(WmsInoutFlowMapper.class);
		when(flows.insert(any(WmsInoutFlowEntity.class))).thenAnswer(c -> {
			if (zeroWrite.equals("flow")) {
				return 0;
			}
			WmsInoutFlowEntity row = c.getArgument(0);
			return db.update("insert into flow values(?,?,?,?)", ids.incrementAndGet(), row.getMatNum(),
					row.getBillDetailId(), row.getSourceStockId());
		});
		var notices = mock(WmsInNoticeMapper.class);
		when(notices.lockNotice(anyLong())).thenAnswer(c -> db.queryForObject(
				"select id from notice where id=? for update", Long.class, (Object) c.getArgument(0)));
		when(notices.selectById(any(java.io.Serializable.class))).thenAnswer(
				c -> db.queryForObject("select * from notice where id=?", noticeRow(), (Object) c.getArgument(0)));
		when(notices.selectOne(any(Wrapper.class), eq(false)))
				.thenAnswer(c -> first(query("notice", c.getArgument(0), noticeRow())));
		when(notices.selectOne(any(Wrapper.class)))
				.thenAnswer(c -> first(query("notice", c.getArgument(0), noticeRow())));
		when(notices.updateById(any(WmsInNoticeEntity.class))).thenAnswer(c -> {
			WmsInNoticeEntity row = c.getArgument(0);
			if (zeroWrite.equals("notice") || (zeroWrite.equals("intermediate_notice")
					&& "RECEIVING".equals(row.getReturnReceiptStatus()))) {
				return 0;
			}
			return db.update("update notice set state=?,return_receipt_status=?,return_overage_num=?,"
							+ "return_shortage_num=?,return_reporter_code=?,return_disposition=?,"
							+ "return_disposition_reason=?,return_reviewer_code=? where id=?",
					row.getState(), row.getReturnReceiptStatus(), row.getReturnOverageNum(), row.getReturnShortageNum(),
					row.getReturnReporterCode(), row.getReturnDisposition(), row.getReturnDispositionReason(),
					row.getReturnReviewerCode(), row.getId());
		});
		var bills = mock(WmsInBillMapper.class);
		when(bills.selectList(any(Wrapper.class))).thenAnswer(c -> query("bill", c.getArgument(0), billRow()));
		when(bills.update(any(Wrapper.class))).thenAnswer(c -> update("bill", c.getArgument(0)));
		var billDetails = mock(WmsInBillDetailMapper.class);
		when(billDetails.selectList(any(Wrapper.class)))
				.thenAnswer(c -> query("bill_detail", c.getArgument(0), billDetailRow()));
		when(billDetails.update(any(Wrapper.class)))
				.thenAnswer(c -> zeroWrite.equals("bill_detail") ? 0 : update("bill_detail", c.getArgument(0)));
		var outbox = mock(WmsMesMaterialReturnReceiptOutboxMapper.class);
		when(outbox.selectOne(any(Wrapper.class))).thenAnswer(c -> first(query("outbox", c.getArgument(0), (r, n) -> {
			var row = new WmsMesMaterialReturnReceiptOutboxEntity().setEventId(r.getString("event_id"))
					.setNoticeId(r.getLong("notice_id")).setFingerprint(r.getString("fingerprint"))
					.setPayloadJson(r.getString("payload_json")).setStatus(r.getString("status"));
			row.setId(r.getLong("id"));
			return row;
		})));
		when(outbox.insert(any(WmsMesMaterialReturnReceiptOutboxEntity.class))).thenAnswer(c -> {
			if (zeroWrite.equals("outbox")) {
				return 0;
			}
			WmsMesMaterialReturnReceiptOutboxEntity row = c.getArgument(0);
			return db.update("insert into outbox(event_id,notice_id,fingerprint,payload_json,status) values(?,?,?,?,?)",
					row.getEventId(), row.getNoticeId(), row.getFingerprint(), row.getPayloadJson(), row.getStatus());
		});
		var operations = mock(WmsProductionReturnReceiptOperationMapper.class);
		when(operations.selectOne(any(Wrapper.class)))
				.thenAnswer(c -> first(query("operation", c.getArgument(0),
						(r, n) -> new WmsProductionReturnReceiptOperationEntity()
								.setRequestId(r.getString("request_id"))
								.setNoticeId(r.getLong("notice_id")).setOperationType(r.getString("operation_type")))));
		when(operations.selectList(any(Wrapper.class))).thenReturn(List.of());
		when(operations.insert(any(WmsProductionReturnReceiptOperationEntity.class))).thenAnswer(c -> {
			if (zeroWrite.equals("operation")) {
				return 0;
			}
			WmsProductionReturnReceiptOperationEntity row = c.getArgument(0);
			return db.update("insert into operation(request_id,notice_id,operation_type) values(?,?,?)",
					row.getRequestId(), row.getNoticeId(), row.getOperationType());
		});
		var outboxService = proxy(new WmsMesMaterialReturnReceiptOutboxService(outbox, notices, bills,
				billDetails, null, new DataSourceTransactionManager(source)), source);
		var inboundTarget = new WmsProductionReturnInBillService(outboxService) {
			@Override
			public Boolean checkWarehouse(String warehouse, String type) {
				return true;
			}
		};
		ReflectionTestUtils.setField(inboundTarget, "wmsInNoticeMapper", notices);
		ReflectionTestUtils.setField(inboundTarget, "wmsLiveStockMapper", stocks);
		ReflectionTestUtils.setField(inboundTarget, "wmsInoutFlowMapper", flows);
		ReflectionTestUtils.setField(inboundTarget, "wmsMaterialReservationService", reservations);
		ReflectionTestUtils.setField(inboundTarget, "identifierPolicyService", mock(WmsIdentifierPolicyService.class));
		ReflectionTestUtils.setField(inboundTarget, "warehouseBusinessGuard", mock(WmsWarehouseBusinessGuard.class));
		inbound = proxy(inboundTarget, source);
		var permissions = mock(PermissionService.class);
		for (var action : ReturnReceiptActionEnum.values()) {
			when(permissions.hasPermission(action.permission())).thenReturn(true);
		}
		when(permissions.hasPermission("wms_notice_view_return_W1")).thenReturn(true);
		var guard = mock(WmsWarehouseBusinessGuard.class);
		when(guard.returnReceiptActions("W1")).thenReturn(java.util.EnumSet.allOf(ReturnReceiptActionEnum.class));
		var access = new WmsReturnReceiptAccessService(permissions, guard);
		service = proxy(new WmsProductionReturnReceiptService(notices, bills, billDetails, operations,
				outbox, inbound, outboxService, access), source);
		var configuration = new MybatisConfiguration();
		configuration.addMapper(WmsReturnFinalizeMapper.class);
		var sqlFactory = new MybatisSqlSessionFactoryBean();
		sqlFactory.setDataSource(source);
		sqlFactory.setConfiguration(configuration);
		var receiptMapper = new SqlSessionTemplate(sqlFactory.getObject()).getMapper(WmsReturnFinalizeMapper.class);
		mobileGuard = mock(WmsWarehouseBusinessGuard.class);
		finalization = proxy(new WmsReturnFinalizeService(receiptMapper, notices, operations,
				service, access, new ObjectMapper(), mobileGuard), source);
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	@SuppressWarnings("unchecked")
	private <T> T proxy(T target, DriverManagerDataSource source) {
		var factory = new ProxyFactory(target);
		factory.setProxyTargetClass(true);
		factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
				new AnnotationTransactionAttributeSource()));
		return (T) factory.getProxy();
	}

	private void addBill(long id, long detailId, double amount) {
		db.update("insert into bill values(?,20,'11',null,?,0,1)", id, "IN-" + id);
		db.update("insert into bill_detail values(?,?,?,null)", detailId, id, amount);
	}

	private RowMapper<WmsInNoticeEntity> noticeRow() {
		return (r, n) -> {
			var row = new WmsInNoticeEntity();
			row.setFactoryCode(r.getString("factory_code"));
			row.setTenantId(1L);
			row.setWarehouseCode("W1");
			row.setState(r.getString("state"));
			row.setReturnReceiptStatus(r.getString("return_receipt_status"));
			row.setNoticeInNum(r.getDouble("notice_in_num"));
			row.setReturnOverageNum(r.getBigDecimal("return_overage_num"));
			row.setReturnShortageNum(r.getBigDecimal("return_shortage_num"));
			row.setReturnReporterCode(r.getString("return_reporter_code"));
			row.setReturnDisposition(r.getString("return_disposition"));
			row.setReturnDispositionReason(r.getString("return_disposition_reason"));
			row.setReturnReviewerCode(r.getString("return_reviewer_code"));
			row.setBillType(InBillTypeEnum.RETURN.getCode());
			row.setDocumentId(40L);
			row.setDocumentTable("mom_mes.mes_warehouse_stock_feed_judge");
			row.setId(r.getLong("id"));
			return row;
		};
	}

	private RowMapper<WmsInBillEntity> billRow() {
		return (r, n) -> {
			var row = new WmsInBillEntity();
			row.setNoticeId(r.getLong("notice_id"));
			row.setState(r.getString("state"));
			row.setBillNo(r.getString("bill_no"));
			row.setBillType(InBillTypeEnum.RETURN.getCode());
			row.setWarehouseCode("W1");
			row.setFactoryCode("F1");
			Timestamp stamp = r.getTimestamp("in_time");
			row.setInTime(stamp == null ? null : stamp.toLocalDateTime());
			row.setId(r.getLong("id"));
			return row;
		};
	}

	private RowMapper<WmsInBillDetailEntity> billDetailRow() {
		return (r, n) -> {
			var row = returned(r.getDouble("mat_num"));
			row.setId(r.getLong("id"));
			row.setBillId(r.getLong("bill_id"));
			return row;
		};
	}

	private RowMapper<WmsMaterialReservationDetailEntity> detailRow() {
		return (r, n) -> {
			var row = new WmsMaterialReservationDetailEntity();
			row.setReservationId(r.getLong("reservation_id"));
			row.setLiveStockId(r.getLong("live_stock_id"));
			row.setActualMatCode(r.getString("actual_mat_code"));
			row.setRequirementMatCode(r.getString("requirement_mat_code"));
			row.setReservedNum(r.getBigDecimal("reserved_num"));
			row.setConsumedNum(r.getBigDecimal("consumed_num"));
			row.setReturnedNum(r.getBigDecimal("returned_num"));
			row.setReleasedNum(r.getBigDecimal("released_num"));
			row.setSubstituteRatio(r.getBigDecimal("substitute_ratio"));
			row.setStatus(r.getString("status"));
			row.setId(r.getLong("id"));
			return row;
		};
	}

	private WmsInBillDetailEntity returned(Double quantity) {
		var row = new WmsInBillDetailEntity().setMatNum(quantity).setMatCode("M1").setMatUnit("KG")
				.setRequirementMatCode("M1").setReservationNo("RES-10").setFactoryCode("F1")
				.setWarehouseCode("W1").setSiteCode("S1").setMatBatch("B1").setInTime(time);
		row.setId(301L);
		row.setBillId(30L);
		return row;
	}

	private WmsInBillEntity bill(Double quantity) {
		var row = new WmsInBillEntity();
		row.setBillNo("IN-30");
		row.setBillType(InBillTypeEnum.RETURN.getCode());
		row.setWarehouseCode("W1");
		row.setDetails(List.of(returned(quantity)));
		row.setId(30L);
		return row;
	}

	private record Bound(String text, Object[] values) {
	}

	private Bound bind(String sql, Wrapper<?> wrapper) {
		var matcher = Pattern.compile("#\\{ew\\.paramNameValuePairs\\.(\\w+)(?:,[^}]*)?}").matcher(sql);
		var values = new ArrayList<Object>();
		var result = new StringBuilder();
		var params = ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
		while (matcher.find()) {
			values.add(params.get(matcher.group(1)));
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
		var changes = (LambdaUpdateWrapper<?>) wrapper;
		var sql = bind("update " + table + " set " + changes.getSqlSet() + " " + changes.getCustomSqlSegment(),
				wrapper);
		return db.update(sql.text(), sql.values());
	}

	private <T> T first(List<T> rows) {
		return rows.isEmpty() ? null : rows.get(0);
	}

	private int count(String table) {
		return db.queryForObject("select count(*) from " + table, Integer.class);
	}

	private double returnedAmount() {
		return db.queryForObject("select returned_num from reservation_detail where id=101", Double.class);
	}

	private String reservationStatus() {
		return db.queryForObject("select status from reservation where id=10", String.class);
	}

	private void unchanged() {
		assertEquals(0, count("mom_wms.wms_return_finalize_receipt"));
		assertEquals(0, count("stock"));
		assertEquals(0, count("flow"));
		assertEquals(0d, returnedAmount());
		assertEquals(1, count("reservation_detail"));
		assertEquals("CONSUMED", reservationStatus());
		assertEquals(0, count("outbox"));
	}

	private List<MesMaterialReturnReceiptEvent> events() {
		return db.query("select payload_json from outbox order by id",
				(r, n) -> JSONUtil.toBean(r.getString(1), MesMaterialReturnReceiptEvent.class));
	}

	private WmsProductionReturnReceiptCommand.View finalizeReturn() {
		var actor = mock(MomUser.class);
		when(actor.getUsername()).thenReturn("receiver");
		when(actor.getName()).thenReturn("收货员");
		SecurityContextHolder.getContext()
				.setAuthentication(new UsernamePasswordAuthenticationToken(actor, "N/A", List.of()));
		return finalization.finalizeLegacy(new WmsProductionReturnReceiptCommand.Finalize().setNoticeId(20L)
				.setRequestId("return-finalize-0001").setReason("现场收货完成"));
	}

	private Command mobileCommand() {
		var actor = mock(MomUser.class);
		when(actor.getUsername()).thenReturn("receiver");
		when(actor.getName()).thenReturn("收货员");
		when(actor.getTenantId()).thenReturn(1L);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(actor, "N/A", List.of()));
		return new Command(
				"return-mobile-0001", 20L, "W1", BigDecimal.ZERO, "现场收货完成");
	}

	@Test
	void actualMobileFinalizationPostsAndClosedWarehouseReplayDoesNotDuplicateAnyBusinessEvidence() {
		var command = mobileCommand();
		var first = finalization.finalizeMobile(command);
		clearInvocations(mobileGuard);
		doThrow(new CheckedException("手机端未开启")).when(mobileGuard).checkMobile("W1");

		assertEquals(first, finalization.finalizeMobile(command));
		assertEquals("RECEIVED", first.result().returnReceiptStatus());
		assertEquals(0, new BigDecimal("10").compareTo(first.result().actualReceivedQuantity()));
		assertEquals(2, count("stock"));
		assertEquals(2, count("flow"));
		assertEquals(10d, db.queryForObject("select sum(mat_num) from stock", Double.class));
		assertEquals(10d, returnedAmount());
		assertEquals(3, count("outbox"));
		assertEquals(1, count("mom_wms.wms_return_finalize_receipt"));
		verify(mobileGuard, never()).checkMobile(anyString());
	}

	@Test
	void disabledMobileFinalizationLeavesStockReservationBillsAndOutboxUntouched() {
		doThrow(new CheckedException("手机端未开启")).when(mobileGuard).checkMobile("W1");

		assertEquals("手机端未开启", assertThrows(CheckedException.class,
				() -> finalization.finalizeMobile(mobileCommand())).getMessage());

		unchanged();
		assertEquals(2, db.queryForObject("select count(*) from bill where state='11'", Integer.class));
		assertEquals("WAITING", db.queryForObject("select return_receipt_status from notice", String.class));
	}

	@Test
	void secondMobileBillFailureRollsBackFirstBillStockFlowReservationReceiptAndOutbox() {
		failDetailId = 311;

		assertThrows(CheckedException.class, () -> finalization.finalizeMobile(mobileCommand()));

		unchanged();
		assertEquals(2, db.queryForObject("select count(*) from bill where state='11'", Integer.class));
		assertEquals("WAITING", db.queryForObject("select return_receipt_status from notice", String.class));
		verify(mobileGuard).checkMobile("W1");
	}

	@Test
	void actualReturnPostsStockFlowAndReopensOnlyReturnedReservationQuantity() {
		inbound.afterInStock(bill(4d));

		assertEquals(1, count("stock"));
		assertEquals(1, count("flow"));
		assertEquals(4d, returnedAmount());
		assertEquals("ACTIVE", reservationStatus());
		assertEquals(4d, db.queryForObject("select reserved_num from reservation_detail where parent_detail_id=101",
				Double.class));
		assertEquals(1, db.queryForObject("select count(*) from stock s join flow f on s.id=f.source_stock_id "
						+ "join reservation_detail d on d.live_stock_id=s.id where d.return_bill_detail_id=301",
				Integer.class));
	}

	@Test
	void releasedDemandReturnsAsFreeStockWithoutReactivation() {
		db.update("update reservation set status='RELEASED' where id=10");

		inbound.afterInStock(bill(4d));

		assertEquals(1, count("stock"));
		assertEquals(1, count("flow"));
		assertEquals(1, count("reservation_detail"));
		assertEquals(0d, returnedAmount());
		assertEquals("RELEASED", reservationStatus());
	}

	@Test
	void invalidReservationStateCannotBeReactivatedByAProductionReturn() {
		db.update("update reservation set status='BROKEN' where id=10");

		assertThrows(CheckedException.class,
				() -> reservations.replenishFromProductionReturn(returned(4d), 900L));
		assertEquals("BROKEN", reservationStatus());
		assertEquals(0d, returnedAmount());
		assertEquals(1, count("reservation_detail"));
	}

	@Test
	void zeroStockInsertCannotCreateReservationOrFlow() {
		zeroWrite = "stock";

		assertThrows(CheckedException.class, () -> inbound.afterInStock(bill(4d)));
		unchanged();
	}

	@Test
	void zeroFlowInsertRollsBackStockAndReservation() {
		zeroWrite = "flow";

		assertThrows(CheckedException.class, () -> inbound.afterInStock(bill(4d)));
		unchanged();
	}

	@Test
	void zeroSourceReturnedUpdateCannotAddNewReservation() {
		zeroWrite = "returned";

		assertThrows(CheckedException.class, () -> inbound.afterInStock(bill(4d)));
		unchanged();
	}

	@Test
	void zeroReplenishmentInsertRollsBackSourceAndStock() {
		zeroWrite = "replenish";

		assertThrows(CheckedException.class, () -> inbound.afterInStock(bill(4d)));
		unchanged();
	}

	@Test
	void zeroReservationReactivationRollsBackAllReceiptFacts() {
		zeroWrite = "reservation";

		assertThrows(CheckedException.class, () -> inbound.afterInStock(bill(4d)));
		unchanged();
	}

	@Test
	void excessReturnCannotExceedOriginalConsumption() {
		assertThrows(CheckedException.class, () -> inbound.afterInStock(bill(11d)));
		unchanged();
	}

	@Test
	void directReplenishmentRejectsNonFiniteQuantityAsBusinessFailure() {
		for (Double quantity : List.of(Double.NaN, Double.POSITIVE_INFINITY)) {
			assertThrows(CheckedException.class,
					() -> reservations.replenishFromProductionReturn(returned(quantity), 900L));
			unchanged();
		}
	}

	@Test
	void unreservedInboundStillRejectsInvalidPhysicalQuantity() {
		for (Double quantity : Arrays.asList(null, 0d, -1d, Double.NaN, Double.POSITIVE_INFINITY)) {
			var input = bill(quantity);
			input.getDetails().get(0).setReservationNo(null);

			assertThrows(CheckedException.class, () -> inbound.afterInStock(input));
			unchanged();
		}
	}

	@Test
	void repeatedOriginalReplenishmentReturnsOriginalResultWithoutDoubleReservation() {
		assertTrue(reservations.replenishFromProductionReturn(returned(4d), 900L));
		assertTrue(reservations.replenishFromProductionReturn(returned(4d), 900L));

		assertEquals(4d, returnedAmount());
		assertEquals(2, count("reservation_detail"));
		assertThrows(CheckedException.class, () -> reservations.replenishFromProductionReturn(returned(3d), 900L));
		assertThrows(CheckedException.class, () -> reservations.replenishFromProductionReturn(returned(4d), 901L));
		assertEquals(4d, returnedAmount());
	}

	@Test
	void legacyReturnWithoutReceiptStatusCanStillBeFinalized() {
		db.update("update notice set return_receipt_status=null where id=20");
		assertEquals("RECEIVED", finalizeReturn().getNotice().getReturnReceiptStatus());
		assertEquals(2, count("stock"));
	}

	@Test
	void voidedReturnNoticeCannotBeRevivedByFinalization() {
		db.update("update notice set state='3' where id=20");
		assertThrows(CheckedException.class, this::finalizeReturn);
		unchanged();
		assertEquals("3", db.queryForObject("select state from notice where id=20", String.class));
	}

	@Test
	void finalizingTwoBillsEmitsEachActualIncrementBeforeFinalStatus() {
		var result = finalizeReturn();
		finalizeReturn();
		var events = events();

		assertEquals(0, result.getActualReceivedQuantity().compareTo(new BigDecimal("10")));
		assertEquals(3, events.size());
		assertEquals(0, events.get(0).getReceiptQuantity().compareTo(new BigDecimal("4")));
		assertEquals(0, events.get(0).getCumulativeReceivedQuantity().compareTo(new BigDecimal("4")));
		assertEquals(0, events.get(1).getReceiptQuantity().compareTo(new BigDecimal("6")));
		assertEquals(0, events.get(1).getCumulativeReceivedQuantity().compareTo(new BigDecimal("10")));
		assertEquals(0, events.get(2).getReceiptQuantity().signum());
		assertEquals("RECEIVED", events.get(2).getReceiptStatus());
		assertTrue(events.get(2).getFinalReceipt());
		assertEquals(2, count("stock"));
		assertEquals(2, count("flow"));
		assertEquals(10d, returnedAmount());
		assertEquals(1, count("mom_wms.wms_return_finalize_receipt"));
		for (var event : events) {
			assertEquals(event.getFingerprint(), event.calculateFingerprint());
		}
	}

	@Test
	void shortReceiptKeepsActualIncrementSeparateFromVarianceState() {
		db.update("delete from bill_detail where bill_id=31");
		db.update("delete from bill where id=31");

		var result = finalizeReturn();
		var events = events();

		assertEquals(0, result.getShortageQuantity().compareTo(new BigDecimal("6")));
		assertEquals(2, events.size());
		assertEquals("RECEIVING", events.get(0).getReceiptStatus());
		assertEquals(0, events.get(0).getReceiptQuantity().compareTo(new BigDecimal("4")));
		assertEquals("VARIANCE_PENDING", events.get(1).getReceiptStatus());
		assertEquals(0, events.get(1).getReceiptQuantity().signum());
		assertEquals(0, events.get(1).getCumulativeReceivedQuantity().compareTo(new BigDecimal("4")));
		assertFalse(events.get(1).getFinalReceipt());
		assertEquals(4d, returnedAmount());
	}

	@Test
	void noPhysicalReceiptCreatesOnlyVarianceEventWithoutStockOrReservation() {
		db.update("delete from bill_detail");
		db.update("delete from bill");

		finalizeReturn();
		var events = events();

		assertEquals(1, events.size());
		assertEquals(0, events.get(0).getReceiptQuantity().signum());
		assertEquals(0, events.get(0).getCumulativeReceivedQuantity().signum());
		assertEquals("VARIANCE_PENDING", events.get(0).getReceiptStatus());
		assertEquals(0, count("stock"));
		assertEquals(0, count("flow"));
		assertEquals(0d, returnedAmount());
		assertEquals("CONSUMED", reservationStatus());
	}

	@Test
	void laterBillStockFailureRollsBackEarlierReceiptAndFrozenEvent() {
		failDetailId = 311;

		assertThrows(CheckedException.class, this::finalizeReturn);
		unchanged();
		assertEquals(2, db.queryForObject("select count(*) from bill where state='11'", Integer.class));
		assertEquals(0, count("operation"));
	}

	@Test
	void zeroReceiptDetailTimeUpdateRollsBackBeforePosting() {
		zeroWrite = "bill_detail";

		assertThrows(CheckedException.class, this::finalizeReturn);
		unchanged();
	}

	@Test
	void zeroOutboxInsertRollsBackFullReturnFinalization() {
		zeroWrite = "outbox";

		assertThrows(CheckedException.class, this::finalizeReturn);
		unchanged();
	}

	@Test
	void zeroNoticeUpdateCannotPublishReceiptOrKeepStock() {
		zeroWrite = "intermediate_notice";

		assertThrows(CheckedException.class, this::finalizeReturn);
		unchanged();
	}

	@Test
	void zeroFinalizationAuditRollsBackAlreadyFrozenEvents() {
		zeroWrite = "operation";

		assertThrows(CheckedException.class, this::finalizeReturn);
		unchanged();
	}

	@Test
	void sameFinalizeRequestDoesNotPostOrEnqueueAgain() {
		finalizeReturn();
		finalizeReturn();

		assertEquals(2, count("stock"));
		assertEquals(2, count("flow"));
		assertEquals(3, count("outbox"));
		assertEquals(1, count("operation"));
	}

	@Test
	void dispositionAuditFailureRollsBackNoticeAndNewOutboxWithoutTouchingPostedInventory() {
		assertDispositionFailureIsAtomic("operation");
	}

	@Test
	void dispositionOutboxFailureCannotLeaveDispositionOrAuditCommitted() {
		assertDispositionFailureIsAtomic("outbox");
	}

	private void assertDispositionFailureIsAtomic(String failure) {
		db.update("delete from bill_detail where bill_id=31");
		db.update("delete from bill where id=31");
		finalizeReturn();
		int beforeEvents = count("outbox");
		int beforeAudit = count("operation");
		var actor = mock(MomUser.class);
		when(actor.getUsername()).thenReturn("reviewer");
		when(actor.getName()).thenReturn("复核员");
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(actor, "N/A", List.of()));
		zeroWrite = failure;
		var error = assertThrows(CheckedException.class, () -> service.dispose(new WmsProductionReturnReceiptCommand.Disposition()
				.setNoticeId(20L).setRequestId("return-disposition-transaction-01")
				.setDecision("ACCEPT_SHORTAGE").setReason("核对后接受短收")));
		assertTrue(error.getMessage().contains("operation".equals(failure) ? "履历保存失败" : "待投递事实保存失败"));
		assertEquals("VARIANCE_PENDING", db.queryForObject("select return_receipt_status from notice where id=20", String.class));
		assertNull(db.queryForObject("select return_disposition from notice where id=20", String.class));
		assertEquals(beforeEvents, count("outbox"));
		assertEquals(beforeAudit, count("operation"));
		assertEquals(1, count("stock"));
		assertEquals(1, count("flow"));
		assertEquals(4d, returnedAmount());
	}

}
