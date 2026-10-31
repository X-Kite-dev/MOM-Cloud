package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.wms.dto.WmsProductionReturnReceiptCommand;
import com.kitex.mom.wms.dto.app.WmsReturnFinalizeDTO.Command;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.entity.WmsProductionReturnReceiptOperationEntity;
import com.kitex.mom.wms.mapper.WmsInNoticeMapper;
import com.kitex.mom.wms.mapper.WmsProductionReturnReceiptOperationMapper;
import com.kitex.mom.wms.mapper.WmsReturnFinalizeMapper;
import com.kitex.mom.wms.service.impl.WmsProductionReturnReceiptService;
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
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 真实回执 mapper、唯一键与事务；实际退料入账另由 WmsProductionReturnClosureTest 联验。
 */
class WmsReturnFinalizeRecoveryTest {

	private static final String REQUEST = "return-finalize-000001";
	private JdbcTemplate db;
	private WmsReturnFinalizeService service;
	private WmsReturnFinalizeMapper receipts;
	private WmsProductionReturnReceiptService business;
	private WmsProductionReturnReceiptOperationMapper operations;
	private PermissionService permissions;
	private WmsInNoticeEntity notice;
	private boolean failPosting;
	private WmsWarehouseBusinessGuard mobileGuard;

	@BeforeEach
	void setup() throws Exception {
		var source = new DriverManagerDataSource("jdbc:h2:mem:return-finalize-" + UUID.randomUUID()
				+ ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(source);
		db.execute("CREATE SCHEMA mom_wms");
		String sql = Files.readString(Path.of("../database/wms-return-finalize-receipt-pg.sql"));
		int start = sql.indexOf("CREATE TABLE");
		db.execute(sql.substring(start, sql.indexOf(';', start)));
		db.execute("CREATE TABLE posting (quantity numeric)");
		var configuration = new MybatisConfiguration();
		configuration.addMapper(WmsReturnFinalizeMapper.class);
		var factory = new MybatisSqlSessionFactoryBean();
		factory.setDataSource(source);
		factory.setConfiguration(configuration);
		var actual = new SqlSessionTemplate(factory.getObject()).getMapper(WmsReturnFinalizeMapper.class);
		receipts = mock(WmsReturnFinalizeMapper.class, org.mockito.AdditionalAnswers.delegatesTo(actual));
		var notices = mock(WmsInNoticeMapper.class);
		notice = new WmsInNoticeEntity();
		notice.setId(100L);
		notice.setTenantId(1L);
		notice.setFactoryCode("F1");
		notice.setWarehouseCode("W1");
		notice.setBillType("return");
		notice.setDocumentTable("mom_mes.mes_warehouse_stock_feed_judge");
		when(notices.selectById(100L)).thenReturn(notice);
		when(notices.lockNotice(100L)).thenReturn(100L);
		operations = mock(WmsProductionReturnReceiptOperationMapper.class);
		permissions = mock(PermissionService.class);
		when(permissions.hasPermission("wms_return_receipt_finalize")).thenReturn(true);
		when(permissions.hasPermission("wms_notice_view_return_W1")).thenReturn(true);
		var access = new WmsReturnReceiptAccessService(permissions, mock(WmsWarehouseBusinessGuard.class));
		business = mock(WmsProductionReturnReceiptService.class);
		when(business.finalizeReceipt(any())).thenAnswer(c -> {
			db.update("INSERT INTO posting VALUES (4)");
			if (failPosting) {
				throw new CheckedException("入库记账失败");
			}
			notice.setReturnReceiptStatus("VARIANCE_PENDING");
			return new WmsProductionReturnReceiptCommand.View().setNotice(notice)
					.setActualReceivedQuantity(new BigDecimal("4"))
					.setShortageQuantity(new BigDecimal("6")).setOverageQuantity(BigDecimal.ZERO);
		});
		mobileGuard = mock(WmsWarehouseBusinessGuard.class);
		var target = new WmsReturnFinalizeService(receipts, notices, operations, business,
				access, new ObjectMapper(), mobileGuard);
		var proxy = new ProxyFactory(target);
		proxy.setProxyTargetClass(true);
		proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
				new AnnotationTransactionAttributeSource()));
		service = (WmsReturnFinalizeService) proxy.getProxy();
		scope("receiver", "F1", 1L);
	}

	@AfterEach
	void clear() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
		SecurityContextHolder.clearContext();
	}

	@Test
	void originalResultRemainsStableAfterLaterDisposition() {
		var first = service.finalizeReceipt(command());
		notice.setReturnReceiptStatus("CLOSED_WITH_VARIANCE");
		assertEquals(first, service.finalizeReceipt(command()));
		assertEquals(first, service.lookup(REQUEST, 100L, "W1"));
		assertEquals("VARIANCE_PENDING", first.result().returnReceiptStatus());
		assertEquals(1, count("posting"));
	}

	@Test
	void sameRequestWithChangedOverageOrReasonCannotReplay() {
		service.finalizeReceipt(command());
		assertThrows(CheckedException.class, () -> service.finalizeReceipt(
				new Command(REQUEST, 100L, "W1", BigDecimal.ONE, "现场短收")));
		assertThrows(CheckedException.class, () -> service.finalizeReceipt(
				new Command(REQUEST, 100L, "W1", BigDecimal.ZERO, "改了原因")));
		assertEquals(1, count("posting"));
	}

	@Test
	void postingFailureRollsBackReceiptAndBusinessWrites() {
		failPosting = true;
		assertThrows(CheckedException.class, () -> service.finalizeReceipt(command()));
		assertEquals(0, count("posting"));
		assertEquals(0, count("mom_wms.wms_return_finalize_receipt"));
		assertEquals("NOT_CONFIRMED", service.lookup(REQUEST, 100L, "W1").status());
	}

	@Test
	void missingCompletionUpdateRollsBackBusinessWrites() {
		doReturn(0).when(receipts).complete(any(), anyString(), anyString(), anyString(),
				anyString(), anyString(), any(), any(), any());
		assertThrows(CheckedException.class, () -> service.finalizeReceipt(command()));
		assertEquals(0, count("posting"));
		assertEquals(0, count("mom_wms.wms_return_finalize_receipt"));
	}

	@Test
	void cancellationSealsTheRequestWithoutPostingStock() {
		assertEquals("CANCELLED", service.cancel(command(), "重新核对").status());
		assertEquals("CANCELLED", service.finalizeReceipt(command()).status());
		assertEquals(0, count("posting"));
	}

	@Test
	void cancellationCannotUndoAlreadyConfirmedFinalization() {
		var result = service.finalizeReceipt(command());
		assertEquals(result, service.cancel(command(), "查询原结果"));
		assertEquals(1, count("posting"));
	}

	@Test
	void resultReadRequiresOriginalActorFactoryWarehouseAndPermission() {
		service.finalizeReceipt(command());
		scope("reviewer", "F1", 1L);
		assertThrows(CheckedException.class, () -> service.lookup(REQUEST, 100L, "W1"));
		scope("receiver", "F2", 1L);
		assertThrows(CheckedException.class, () -> service.lookup(REQUEST, 100L, "W1"));
		scope("receiver", "F1", 1L);
		assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> service.lookup(REQUEST, 100L, "W2"));
		when(permissions.hasPermission("wms_return_receipt_finalize")).thenReturn(false);
		assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> service.lookup(REQUEST, 100L, "W1"));
	}

	@Test
	void skippedTenantOrFactoryContextCannotReadOrWrite() {
		TenantContextHolder.setTenantSkip();
		assertThrows(CheckedException.class, () -> service.finalizeReceipt(command()));
		TenantContextHolder.clear();
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactorySkip();
		assertThrows(CheckedException.class, () -> service.lookup(REQUEST, 100L, "W1"));
	}

	@Test
	void wrongNoticeWarehouseAndNonMesReturnCannotPost() {
		notice.setWarehouseCode("W2");
		assertThrows(CheckedException.class, () -> service.finalizeReceipt(command()));
		notice.setWarehouseCode("W1");
		notice.setDocumentTable("other");
		assertThrows(CheckedException.class, () -> service.finalizeReceipt(command()));
		assertEquals(0, count("posting"));
	}

	@Test
	void legacyAuditCannotBeClaimedByAnotherOperatorOrChangedPayload() {
		var old = new WmsProductionReturnReceiptOperationEntity()
				.setRequestId(REQUEST).setNoticeId(100L).setOperationType("FINALIZE")
				.setOperatorCode("another").setFactoryCode("F1");
		old.setTenantId(1L);
		when(operations.selectOne(any())).thenReturn(old);
		assertThrows(CheckedException.class, () -> service.finalizeReceipt(command()));
		old.setOperatorCode("receiver").setDetail("{}");
		assertThrows(CheckedException.class, () -> service.cancel(command(), "核对"));
		assertEquals(0, count("posting"));
	}

	@Test
	void malformedBusinessOutcomeCannotBeSealedAsSuccess() {
		notice.setReturnReceiptStatus("VARIANCE_PENDING");
		doReturn(new WmsProductionReturnReceiptCommand.View()
				.setNotice(notice).setActualReceivedQuantity(BigDecimal.ZERO)
				.setShortageQuantity(BigDecimal.ZERO).setOverageQuantity(BigDecimal.ZERO))
				.when(business).finalizeReceipt(any());
		assertThrows(CheckedException.class, () -> service.finalizeReceipt(command()));
		assertEquals(0, count("mom_wms.wms_return_finalize_receipt"));
	}

	@Test
	void verifiedHistoricalSnapshotIsImportedWithoutPostingAgain() {
		var old = new WmsProductionReturnReceiptOperationEntity()
				.setRequestId(REQUEST).setNoticeId(100L).setOperationType("FINALIZE")
				.setOperatorCode("receiver").setFactoryCode("F1").setAfterStatus("VARIANCE_PENDING")
				.setDetail("{\"notice\":{\"id\":100,\"warehouseCode\":\"W1\",\"returnOverageNum\":0,"
						+ "\"returnVarianceReason\":\"现场短收\"},\"actualReceivedQuantity\":4,"
						+ "\"shortageQuantity\":6,\"overageQuantity\":0}");
		old.setTenantId(1L);
		when(operations.selectOne(any())).thenReturn(old);
		assertEquals("CONFIRMED", service.cancel(command(), "核对旧结果").status());
		assertEquals(0, count("posting"));
	}

	@Test
	void concurrentFinalizeAndCancelCanOnlyLeaveOneTerminalResult() throws Exception {
		var gate = new CountDownLatch(1);
		var pool = Executors.newFixedThreadPool(2);
		try {
			var a = pool.submit(() -> {
				scope("receiver", "F1", 1L);
				gate.await();
				return service.finalizeReceipt(command());
			});
			var b = pool.submit(() -> {
				scope("receiver", "F1", 1L);
				gate.await();
				return service.cancel(command(), "现场重新核对");
			});
			gate.countDown();
			assertEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
			assertTrue(count("posting") <= 1);
			assertEquals(1, count("mom_wms.wms_return_finalize_receipt"));
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void newMobileFinalizationRejectsDisabledWarehouseWithoutLeavingReceiptOrPosting() {
		doThrow(new CheckedException("手机端未开启")).when(mobileGuard).checkMobile("W1");

		var failure = assertThrows(CheckedException.class, () -> service.finalizeMobile(command()));

		assertEquals("手机端未开启", failure.getMessage());
		assertEquals(0, count("posting"));
		assertEquals(0, count("mom_wms.wms_return_finalize_receipt"));
		verify(business, never()).finalizeReceipt(any());
	}

	@Test
	void pcFinalizationDoesNotDependOnMobileConfiguration() {
		doThrow(new CheckedException("手机端未开启")).when(mobileGuard).checkMobile(anyString());

		assertEquals("CONFIRMED", service.finalizeReceipt(command()).status());

		assertEquals(1, count("posting"));
		verify(mobileGuard, never()).checkMobile(anyString());
	}

	@Test
	void mobileReplayLookupAndCancellationKeepOriginalResultAfterWarehouseCloses() {
		var first = service.finalizeMobile(command());
		clearInvocations(mobileGuard);
		doThrow(new CheckedException("手机端未开启")).when(mobileGuard).checkMobile(anyString());

		assertEquals(first, service.finalizeMobile(command()));
		assertEquals(first, service.lookup(REQUEST, 100L, "W1"));
		assertEquals(first, service.cancel(command(), "核对原结果"));

		assertEquals(1, count("posting"));
		assertEquals(1, count("mom_wms.wms_return_finalize_receipt"));
		verify(mobileGuard, never()).checkMobile(anyString());
	}

	@Test
	void safeCancellationDoesNotRequireAnOpenMobileWarehouse() {
		doThrow(new CheckedException("手机端未开启")).when(mobileGuard).checkMobile(anyString());

		var cancelled = service.cancel(command(), "重新核对");
		assertEquals("CANCELLED", cancelled.status());
		assertEquals(cancelled, service.finalizeMobile(command()));

		assertEquals(0, count("posting"));
		verify(mobileGuard, never()).checkMobile(anyString());
	}

	@Test
	void originalHistoricalFinalizationCanBeSealedWhenMobileIsDisabled() {
		var old = new WmsProductionReturnReceiptOperationEntity()
				.setRequestId(REQUEST).setNoticeId(100L).setOperationType("FINALIZE")
				.setOperatorCode("receiver").setFactoryCode("F1").setAfterStatus("VARIANCE_PENDING")
				.setDetail("{\"notice\":{\"id\":100,\"warehouseCode\":\"W1\",\"returnOverageNum\":0,"
						+ "\"returnVarianceReason\":\"现场短收\"},\"actualReceivedQuantity\":4,"
						+ "\"shortageQuantity\":6,\"overageQuantity\":0}");
		old.setTenantId(1L);
		when(operations.selectOne(any())).thenReturn(old);
		doThrow(new CheckedException("手机端未开启")).when(mobileGuard).checkMobile(anyString());

		assertEquals("CONFIRMED", service.finalizeMobile(command()).status());

		assertEquals(0, count("posting"));
		verify(mobileGuard, never()).checkMobile(anyString());
	}

	@Test
	void mobileBusinessFailureRollsBackReservedReceiptAndPosting() {
		failPosting = true;

		assertThrows(CheckedException.class, () -> service.finalizeMobile(command()));

		assertEquals(0, count("posting"));
		assertEquals(0, count("mom_wms.wms_return_finalize_receipt"));
		verify(mobileGuard).checkMobile("W1");
	}

	@Test
	void mobileContextMatchesActualNoticeAndScopeAndFailsClosedForUnavailableConfiguration() {
		when(mobileGuard.mobileEnabled("W1")).thenReturn(true);
		var context = service.mobileContext(100L, "W1");

		assertEquals(1, context.contractVersion());
		assertEquals(1, context.mobileContractVersion());
		assertEquals(1L, context.tenantId());
		assertEquals("F1", context.factoryCode());
		assertEquals("receiver", context.operatorCode());
		assertEquals(100L, context.noticeId());
		assertEquals("W1", context.warehouseCode());
		assertTrue(context.mobileEnabled());
		when(mobileGuard.mobileEnabled("W1")).thenReturn(false);
		assertFalse(service.mobileContext(100L, "W1").mobileEnabled());
		assertEquals("该仓库未开启手机端作业", service.mobileContext(100L, "W1").mobileMessage());
		when(mobileGuard.mobileEnabled("W1")).thenThrow(new CheckedException("配置服务失败"));
		assertFalse(service.mobileContext(100L, "W1").mobileEnabled());
		when(permissions.hasPermission("wms_return_receipt_finalize")).thenReturn(false);
		assertThrows(org.springframework.security.access.AccessDeniedException.class,
				() -> service.mobileContext(100L, "W1"));
	}

	@Test
	void mobileScopeAndNoticeMismatchCannotBeHiddenByCapabilityProbeFailure() {
		when(mobileGuard.mobileEnabled(anyString())).thenThrow(new CheckedException("配置服务失败"));
		notice.setWarehouseCode("W2");
		assertThrows(CheckedException.class, () -> service.mobileContext(100L, "W1"));
		assertThrows(CheckedException.class, () -> service.finalizeMobile(command()));
		notice.setWarehouseCode("W1");
		var actor = (MomUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
		when(actor.getTenantId()).thenReturn(2L);
		assertThrows(CheckedException.class, () -> service.mobileContext(100L, "W1"));
		assertThrows(CheckedException.class, () -> service.finalizeMobile(command()));

		verify(mobileGuard, never()).mobileEnabled(anyString());
		verify(mobileGuard, never()).checkMobile(anyString());
		assertEquals(0, count("mom_wms.wms_return_finalize_receipt"));
	}

	private Command command() {
		return new Command(REQUEST, 100L, "W1", BigDecimal.ZERO, "现场短收");
	}

	private int count(String table) {
		return db.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

	private void scope(String operator, String factory, Long tenant) {
		TenantContextHolder.setTenantId(tenant);
		FactoryContextHolder.setFactoryCode(factory);
		var actor = mock(MomUser.class);
		when(actor.getUsername()).thenReturn(operator);
		when(actor.getTenantId()).thenReturn(tenant);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(actor, "N/A", List.of()));
	}
}
