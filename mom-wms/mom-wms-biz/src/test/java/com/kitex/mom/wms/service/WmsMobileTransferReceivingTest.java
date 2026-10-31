package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.dto.WmsTransferReceiptDTO;
import com.kitex.mom.wms.dto.app.WmsMobileTransferReceiptDTO.Command;
import com.kitex.mom.wms.entity.WmsAllocationBillEntity;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.entity.WmsTransferReceiptEntity;
import com.kitex.mom.wms.entity.WmsTransferReceiptLineEntity;
import com.kitex.mom.wms.mapper.WmsAllocationBillMapper;
import com.kitex.mom.wms.mapper.WmsInoutFlowMapper;
import com.kitex.mom.wms.mapper.WmsTransferReceiptLineMapper;
import com.kitex.mom.wms.mapper.WmsTransferReceiptMapper;
import com.kitex.mom.wms.service.impl.WmsTransferVarianceServiceImpl;
import com.kitex.mom.wms.vo.WmsAllocationBillVO;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 真正执行取消mapper和Spring事务；接收业务边界写同一数据源，检查提交和回滚。
 */
class WmsMobileTransferReceivingTest {
	private JdbcTemplate db;
	private WmsMobileTransferReceiptService service;
	private WmsTransferReceiptMapper receipts;
	private WmsAllocationBillMapper bills;
	private WmsTransferVarianceService receiving;
	private PermissionService permissions;
	private WmsAllocationBillEntity bill;
	private boolean failReceipt;
	private DataSourceTransactionManager transactions;

	@BeforeEach
	void setup() throws Exception {
		var source = new DriverManagerDataSource("jdbc:h2:mem:mobile-transfer-" + UUID.randomUUID()
				+ ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
		db = new JdbcTemplate(source);
		db.execute("CREATE SCHEMA mom_wms");
		db.execute("CREATE TABLE mom_wms.wms_transfer_receipt(id bigint primary key,tenant_id bigint,request_id varchar(64),"
				+ "allocation_bill_id bigint,allocation_bill_no varchar(100),factory_code varchar(100),receiver_code varchar(100),"
				+ "receipt_no varchar(100),receipt_status varchar(30),request_snapshot text,receipt_time timestamp,"
				+ "variance_case_id bigint,final_receipt boolean,line_count int,UNIQUE(tenant_id,request_id))");
		db.execute("CREATE TABLE bill_lock(id int primary key)");
		db.update("INSERT INTO bill_lock VALUES(7)");
		db.execute("CREATE TABLE physical_stock(id int primary key,quantity decimal)");
		db.update("INSERT INTO physical_stock VALUES(1,0)");
		String migration = Files.readString(Path.of("../database/wms-mobile-transfer-receiving-pg.sql"));
		int start = migration.indexOf("CREATE TABLE");
		db.execute(migration.substring(start, migration.indexOf(';', start)));
		var config = new MybatisConfiguration();
		config.addMapper(WmsTransferReceiptMapper.class);
		var factory = new MybatisSqlSessionFactoryBean();
		factory.setDataSource(source);
		factory.setConfiguration(config);
		receipts = new SqlSessionTemplate(factory.getObject()).getMapper(WmsTransferReceiptMapper.class);
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "mobile-transfer"),
				WmsAllocationBillEntity.class);
		bill = new WmsAllocationBillEntity().setBillNo("TR-1").setFactoryCode("F1").setNewWarehouseCode("W2")
				.setOldWarehouseCode("W1").setAllocationState("IN_TRANSIT").setSourceDeducted(true);
		bill.setId(7L);
		bill.setTenantId(1L);
		bills = mock(WmsAllocationBillMapper.class);
		when(bills.selectOne(any(Wrapper.class))).thenAnswer(call -> {
			Wrapper<?> query = call.getArgument(0);
			if (query.getCustomSqlSegment().contains("FOR UPDATE")) {
				db.queryForObject("SELECT id FROM bill_lock WHERE id=7 FOR UPDATE", Integer.class);
			}
			return bill;
		});
		permissions = mock(PermissionService.class);
		when(permissions.hasPermission(anyString(), anyString())).thenReturn(true);
		when(permissions.hasPermission(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
		var allocations = mock(WmsAllocationBillService.class);
		var details = new WmsAllocationBillVO();
		details.setId(7L);
		details.setNewWarehouseCode("W2");
		details.setFactoryCode("F1");
		details.setSourceDeducted(true);
		when(allocations.getDetails(7L)).thenReturn(details);
		receiving = mock(WmsTransferVarianceService.class);
		when(receiving.receive(any())).thenAnswer(call -> {
			WmsTransferReceiptDTO.Command command = call.getArgument(0);
			db.update("UPDATE physical_stock SET quantity=quantity+2 WHERE id=1");
			db.update("INSERT INTO mom_wms.wms_transfer_receipt VALUES(100,1,?,7,'TR-1','F1','operator',"
							+ "'RC-1',?,?,CURRENT_TIMESTAMP,NULL,?,1)", command.getRequestId(),
					Boolean.TRUE.equals(command.getFinalReceipt()) ? "COMPLETE" : "PARTIAL",
					WmsTransferReceiptSnapshot.raw(command), command.getFinalReceipt());
			if (failReceipt) {
				throw new CheckedException("模拟接收后续失败");
			}
			return new WmsTransferReceiptDTO.Result().setReceiptId(100L);
		});
		transactions = new DataSourceTransactionManager(source);
		service = proxy(new WmsMobileTransferReceiptService(bills, receipts, allocations, receiving, permissions, mock(WmsWarehouseBusinessGuard.class)));
		identity("operator", "F1", 1L);
	}

	@SuppressWarnings("unchecked")
	private <T> T proxy(T target) {
		var factory = new ProxyFactory(target);
		factory.setProxyTargetClass(true);
		factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
		return (T) factory.getProxy();
	}

	private void identity(String user, String factory, long tenant) {
		TenantContextHolder.setTenantId(tenant);
		FactoryContextHolder.setFactoryCode(factory);
		var actor = mock(MomUser.class);
		when(actor.getUsername()).thenReturn(user);
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
	}

	private Command command() {
		var receipt = new WmsTransferReceiptDTO.Command();
		receipt.setAllocationBillId(7L);
		receipt.setRequestId("WMS-TR-original-000001");
		receipt.setFinalReceipt(false);
		var line = new WmsTransferReceiptDTO.Line();
		line.setSourceFlowId(70L);
		line.setActualReceivedQuantity(new BigDecimal("2"));
		line.setTargetSiteCode("S2");
		receipt.setLines(List.of(line));
		return new Command("W2", receipt);
	}

	@AfterEach
	void clear() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
		SecurityContextHolder.clearContext();
	}

	@Test
	void receivingWarehouseMobileSwitchBlocksOnlyNewExecution() {
		var guard = (WmsWarehouseBusinessGuard) ReflectionTestUtils.getField(service, "warehouseGuard");
		doThrow(new CheckedException("target mobile disabled")).when(guard).checkMobile("W2");

		var error = assertThrows(CheckedException.class, () -> service.receive(command()));

		assertEquals("target mobile disabled", error.getMessage());
		verify(receiving, never()).receive(any());
	}

	@Test
	void disabledReceivingWarehouseCanStillCancelAnUnknownOriginal() {
		var guard = (WmsWarehouseBusinessGuard) ReflectionTestUtils.getField(service, "warehouseGuard");
		doThrow(new CheckedException("mobile disabled")).when(guard).checkMobile("W2");

		assertEquals("CANCELLED", service.cancel(command(), "暂停移动收货").status());
		verify(guard, never()).checkMobile(anyString());
	}

	@Test
	void partialReceiptRemainsOriginalAfterTheBillLaterFinishesAndRetryDoesNotReceiveAgain() {
		var first = service.receive(command());
		assertEquals("CONFIRMED", first.status());
		assertEquals("PARTIAL", first.receiptStatus());
		bill.setAllocationState("RECEIVED");
		assertEquals(first, service.lookup(command().receipt().getRequestId(), 7L, "W2"));
		assertEquals(first, service.receive(command()));
		verify(receiving, times(1)).receive(any());
		assertEquals(2, db.queryForObject("SELECT quantity FROM physical_stock WHERE id=1", Integer.class));
	}

	@Test
	void cancellingAnUnexecutedRequestCreatesNoBusinessReceiptAndOldPcCannotReplayIt() {
		var first = service.cancel(command(), "库位录入错误");
		assertEquals("CANCELLED", first.status());
		assertEquals(first, service.cancel(command(), "重复取消不改原原因"));
		assertEquals(first, service.lookup(command().receipt().getRequestId(), 7L, "W2"));
		assertThrows(CheckedException.class, () -> service.receive(command()));
		var old = mock(WmsTransferVarianceServiceImpl.class, CALLS_REAL_METHODS);
		ReflectionTestUtils.setField(old, "billMapper", bills);
		ReflectionTestUtils.setField(old, "receiptMapper", receipts);
		ReflectionTestUtils.setField(old, "permissionService", permissions);
		assertThrows(CheckedException.class, () -> old.receive(command().receipt()));
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_wms.wms_transfer_receipt", Integer.class));
		assertEquals(0, db.queryForObject("SELECT quantity FROM physical_stock WHERE id=1", Integer.class));
	}

	@Test
	void cancellationAfterReceiptReturnsCompletionAndChangedPayloadOrActorCannotClaimIt() {
		var result = service.receive(command());
		assertEquals(result, service.cancel(command(), "核对原结果"));
		var changed = command();
		changed.receipt().getLines().get(0).setActualReceivedQuantity(BigDecimal.ONE);
		assertThrows(CheckedException.class, () -> service.receive(changed));
		identity("other", "F1", 1);
		assertThrows(CheckedException.class, () -> service.lookup(command().receipt().getRequestId(), 7L, "W2"));
	}

	@Test
	void wrongTargetFactoryTenantAndRevokedPermissionsCannotReadOrMutateTheOriginal() {
		assertThrows(CheckedException.class, () -> service.sources("TR-1", "W1"));
		identity("operator", "F2", 1);
		assertThrows(CheckedException.class, () -> service.receive(command()));
		identity("operator", "F1", 2);
		assertThrows(CheckedException.class, () -> service.receive(command()));
		identity("operator", "F1", 1);
		when(permissions.hasPermission(anyString(), anyString())).thenReturn(false);
		when(permissions.hasPermission(anyString(), anyString(), anyString(), anyString())).thenReturn(false);
		assertThrows(CheckedException.class, () -> service.receive(command()));
		verifyZeroInteractions(receiving);
	}

	@Test
	void failedBusinessReceiptRollsBackStockAndProofBeforeCancellation() {
		failReceipt = true;
		assertThrows(CheckedException.class, () -> service.receive(command()));
		assertEquals("NOT_CONFIRMED", service.lookup(command().receipt().getRequestId(), 7L, "W2").status());
		assertEquals(0, db.queryForObject("SELECT quantity FROM physical_stock WHERE id=1", Integer.class));
		assertEquals("CANCELLED", service.cancel(command(), "原接收失败").status());
	}

	@Test
	void executionAndCancellationCompeteOnOneRealTransactionalBillLock() throws Exception {
		var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		var start = new java.util.concurrent.CountDownLatch(1);
		try {
			var execute = pool.submit(() -> {
				identity("operator", "F1", 1L);
				start.await();
				try {
					return service.receive(command()).status();
				} catch (CheckedException cancelled) {
					return service.lookup(command().receipt().getRequestId(), 7L, "W2").status();
				}
			});
			var cancel = pool.submit(() -> {
				identity("operator", "F1", 1L);
				start.await();
				return service.cancel(command(), "并发取消").status();
			});
			start.countDown();
			String result = execute.get(10, java.util.concurrent.TimeUnit.SECONDS);
			assertEquals(result, cancel.get(10, java.util.concurrent.TimeUnit.SECONDS));
			int received = "CONFIRMED".equals(result) ? 1 : 0;
			assertEquals(received, db.queryForObject("SELECT count(*) FROM mom_wms.wms_transfer_receipt", Integer.class));
			assertEquals(1 - received, db.queryForObject("SELECT count(*) FROM mom_wms.wms_mobile_transfer_cancel_receipt", Integer.class));
			assertEquals(received * 2, db.queryForObject("SELECT quantity FROM physical_stock WHERE id=1", Integer.class));
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void aPartialRequestCannotBeConfirmedWithAFinalReceiptStatus() {
		service.receive(command());
		db.update("UPDATE mom_wms.wms_transfer_receipt SET receipt_status='COMPLETE'");
		assertThrows(CheckedException.class, () -> service.lookup(command().receipt().getRequestId(), 7L, "W2"));
	}

	@Test
	void oldPcReceivingAlsoRequiresActualTenantAndFactoryWithoutScopeBypass() {
		var old = mock(WmsTransferVarianceServiceImpl.class, CALLS_REAL_METHODS);
		ReflectionTestUtils.setField(old, "billMapper", bills);
		ReflectionTestUtils.setField(old, "receiptMapper", receipts);
		ReflectionTestUtils.setField(old, "permissionService", permissions);
		TenantContextHolder.clear();
		assertThrows(CheckedException.class, () -> old.receive(command().receipt()));
		identity("operator", "F1", 1L);
		FactoryContextHolder.setFactorySkip();
		assertThrows(CheckedException.class, () -> old.receive(command().receipt()));
	}

	@Test
	void realPartialAndFinalReceivingServiceClosesTheSameBillWithIndependentBatchProofs() {
		var real = mock(WmsTransferVarianceServiceImpl.class, CALLS_REAL_METHODS);
		var storedReceipts = mock(WmsTransferReceiptMapper.class);
		when(storedReceipts.cancelled(anyLong(), anyString()))
				.thenAnswer(call -> receipts.cancelled(call.getArgument(0), call.getArgument(1)));
		when(storedReceipts.selectOne(any(Wrapper.class))).thenAnswer(call -> {
			AbstractWrapper<?, ?, ?> query = call.getArgument(0);
			query.getSqlSegment();
			String request = query.getParamNameValuePairs().values().iterator().next().toString();
			return receipts.findOriginal(1L, request);
		});
		when(storedReceipts.selectCount(any(Wrapper.class))).thenAnswer(call ->
				db.queryForObject("SELECT count(*) FROM mom_wms.wms_transfer_receipt", Long.class));
		var ids = new AtomicLong(100);
		when(storedReceipts.insert(any(WmsTransferReceiptEntity.class))).thenAnswer(call -> {
			WmsTransferReceiptEntity row = call.getArgument(0);
			row.setId(ids.getAndIncrement());
			row.setTenantId(1L);
			return db.update("INSERT INTO mom_wms.wms_transfer_receipt VALUES(?,1,?,7,'TR-1','F1','operator',?,?,?,?,NULL,?,?)",
					row.getId(), row.getRequestId(), row.getReceiptNo(), row.getReceiptStatus(), row.getRequestSnapshot(),
					row.getReceiptTime(), row.getFinalReceipt(), row.getLineCount());
		});
		when(storedReceipts.updateById(any(WmsTransferReceiptEntity.class))).thenAnswer(call -> {
			WmsTransferReceiptEntity row = call.getArgument(0);
			return db.update("UPDATE mom_wms.wms_transfer_receipt SET receipt_status=? WHERE id=?", row.getReceiptStatus(), row.getId());
		});
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "mobile-transfer-flows"),
				WmsInoutFlowEntity.class);
		var flowMapper = mock(WmsInoutFlowMapper.class);
		var issue = new WmsInoutFlowEntity();
		issue.setBillId(7L);
		issue.setBillNo("TR-1");
		issue.setTransferAction("ISSUE");
		issue.setMatCode("M1");
		issue.setMatBatch("B1");
		issue.setMatUnit("KG");
		issue.setMatNum(5D);
		issue.setFactoryCode("F1");
		issue.setWarehouseCode("W1");
		issue.setSourceStockId(9L);
		issue.setId(70L);
		issue.setTenantId(1L);
		var incoming = new ArrayList<WmsInoutFlowEntity>();
		when(flowMapper.selectList(any(Wrapper.class))).thenAnswer(call -> {
			AbstractWrapper<?, ?, ?> query = call.getArgument(0);
			query.getSqlSegment();
			return query.getParamNameValuePairs().containsValue("ISSUE") ? List.of(issue) : List.copyOf(incoming);
		});
		when(flowMapper.insert(any(WmsInoutFlowEntity.class))).thenAnswer(call -> {
			incoming.add(call.getArgument(0));
			return 1;
		});
		var receiptLines = mock(WmsTransferReceiptLineMapper.class);
		when(receiptLines.insert(any(WmsTransferReceiptLineEntity.class))).thenReturn(1);
		var warehouse = new MdmWarehouseEntity().setWarehouseCode("W2").setWarehouseName("成品仓")
				.setFactoryCode("F1").setStatus(true);
		var warehouses = mock(RemoteWarehouseService.class);
		when(warehouses.getByCode("W2")).thenReturn(R.ok(warehouse));
		var site = new MdmWarehouseLocationEntity();
		site.setLocationCode("S2");
		site.setWarehouseCode("W2");
		site.setFactoryCode("F1");
		site.setStatus(true);
		var locations = mock(RemoteWarehouseLocationService.class);
		when(locations.getByCode("S2")).thenReturn(R.ok(site));
		var guard = mock(WmsWarehouseBusinessGuard.class);
		when(guard.check("W2", "allocation", true)).thenReturn(true);
		var stock = mock(WmsLiveStockService.class);
		doAnswer(call -> {
			WmsInoutFlowEntity flow = call.getArgument(0);
			db.update("UPDATE physical_stock SET quantity=quantity+? WHERE id=1", flow.getMatNum());
			return null;
		}).when(stock).transferIn(any(), anyString(), anyString(), anyString(), any(), any());
		when(bills.selectById(7L)).thenReturn(bill);
		when(bills.updateById(any(WmsAllocationBillEntity.class))).thenReturn(1);
		ReflectionTestUtils.setField(real, "billMapper", bills);
		ReflectionTestUtils.setField(real, "receiptMapper", storedReceipts);
		ReflectionTestUtils.setField(real, "receiptLineMapper", receiptLines);
		ReflectionTestUtils.setField(real, "flowMapper", flowMapper);
		ReflectionTestUtils.setField(real, "permissionService", permissions);
		ReflectionTestUtils.setField(real, "warehouseBusinessGuard", guard);
		ReflectionTestUtils.setField(real, "remoteWarehouseService", warehouses);
		ReflectionTestUtils.setField(real, "remoteWarehouseLocationService", locations);
		ReflectionTestUtils.setField(real, "liveStockService", stock);
		doAnswer(call -> real.receive(call.getArgument(0))).when(receiving).receive(any());
		var first = service.receive(command());
		assertEquals("PARTIAL", first.receiptStatus());
		assertEquals("PARTIALLY_RECEIVED", bill.getAllocationState());
		var finalCommand = command();
		finalCommand.receipt().setRequestId("WMS-TR-final-000002");
		finalCommand.receipt().setFinalReceipt(true);
		finalCommand.receipt().getLines().get(0).setActualReceivedQuantity(new BigDecimal("3"));
		var last = service.receive(finalCommand);
		assertEquals("COMPLETE", last.receiptStatus());
		assertEquals("RECEIVED", bill.getAllocationState());
		assertEquals(5, db.queryForObject("SELECT quantity FROM physical_stock WHERE id=1", Integer.class));
		assertEquals(2, incoming.size());
		assertEquals(first, service.lookup(command().receipt().getRequestId(), 7L, "W2"));
	}

}
