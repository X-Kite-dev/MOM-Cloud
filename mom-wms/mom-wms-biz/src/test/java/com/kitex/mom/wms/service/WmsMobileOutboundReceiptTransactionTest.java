package com.kitex.mom.wms.service;

import cn.hutool.extra.spring.SpringUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.wms.dto.app.WmsMobileOutboundReceiptDTO.Command;
import com.kitex.mom.wms.dto.app.WmsOutBillDetailAppDTO;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.mapper.WmsMobileOutboundReceiptMapper;
import com.kitex.mom.wms.mapper.WmsOutNoticeDetailMapper;
import com.kitex.mom.wms.mapper.WmsOutNoticeMapper;
import com.kitex.mom.wms.service.outBill.AbstractWmsOutBillHandler;
import com.kitex.mom.wms.controller.app.AppOutBillController;
import com.kitex.mom.wms.service.outNotice.AbstractWmsOutNoticeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WmsMobileOutboundReceiptTransactionTest {
	private JdbcTemplate db;
	private WmsMobileOutboundReceiptService service;
	private WmsMobileOutboundReceiptMapper receipts;
	private WmsMobileOutboundReceiptMapper sqlReceipts;
	private WmsOutNoticeMapper notices;
	private WmsOutNoticeDetailMapper details;
	private WmsLiveStockMapper stocks;
	private PermissionService permissions;
	private AbstractWmsOutBillHandler handler;
	private Object previousBeanFactory;
	private Object previousApplicationContext;
	private StaticApplicationContext applicationContext;
	private WmsOutNoticeEntity notice;
	private final AtomicInteger postings = new AtomicInteger();
	private boolean failPosting;

	@BeforeEach
	void setup() throws Exception {
		var source = new DriverManagerDataSource("jdbc:h2:mem:outbound-receipt-" + UUID.randomUUID()
				+ ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(source);
		db.execute("CREATE SCHEMA mom_wms");
		String migration = Files.readString(Path.of("../database/wms-mobile-outbound-receipt-pg.sql"));
		int start = migration.indexOf("CREATE TABLE");
		db.execute(migration.substring(start, migration.indexOf(';', start)));
		db.execute("CREATE TABLE posting (request_source bigint, quantity numeric)");
		var configuration = new MybatisConfiguration();
		configuration.addMapper(WmsMobileOutboundReceiptMapper.class);
		var factory = new MybatisSqlSessionFactoryBean();
		factory.setDataSource(source);
		factory.setConfiguration(configuration);
		sqlReceipts = new SqlSessionTemplate(factory.getObject()).getMapper(WmsMobileOutboundReceiptMapper.class);
		receipts = mock(WmsMobileOutboundReceiptMapper.class, org.mockito.AdditionalAnswers.delegatesTo(sqlReceipts));
		notices = mock(WmsOutNoticeMapper.class);
		details = mock(WmsOutNoticeDetailMapper.class);
		stocks = mock(WmsLiveStockMapper.class);
		permissions = mock(PermissionService.class);
		when(permissions.hasPermission("wms_notice_inout_other_out_W1")).thenReturn(true);
		notice = new WmsOutNoticeEntity();
		notice.setId(100L);
		notice.setTenantId(1L);
		notice.setFactoryCode("F1");
		notice.setWarehouseCode("W1");
		notice.setBillType("other_retail");
		when(notices.selectById(100L)).thenReturn(notice);
		when(notices.lockNotice(100L)).thenReturn(100L);
		var detail = new WmsOutNoticeDetailEntity();
		detail.setId(200L);
		detail.setNoticeId(100L);
		detail.setTenantId(1L);
		detail.setFactoryCode("F1");
		detail.setWarehouseCode("W1");
		detail.setMatCode("M1");
		detail.setMatUnit("EA");
		when(details.selectById(200L)).thenReturn(detail);
		var stock = new WmsLiveStockEntity();
		stock.setId(300L);
		stock.setTenantId(1L);
		stock.setFactoryCode("F1");
		stock.setWarehouseCode("W1");
		stock.setMatCode("M1");
		stock.setMatUnit("EA");
		stock.setMatBatch("B1");
		stock.setSiteCode("S1");
		when(stocks.selectById(300L)).thenReturn(stock);
		handler = mock(AbstractWmsOutBillHandler.class);
		when(handler.support("other_retail")).thenReturn(true);
		previousBeanFactory = ReflectionTestUtils.getField(SpringUtil.class, "beanFactory");
		previousApplicationContext = SpringUtil.getApplicationContext();
		applicationContext = new StaticApplicationContext();
		applicationContext.getBeanFactory().registerSingleton("outbound", handler);
		new SpringUtil().postProcessBeanFactory(applicationContext.getBeanFactory());
		new SpringUtil().setApplicationContext(applicationContext);
		when(handler.setOutBillNum(any())).thenAnswer(invocation -> {
			assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
			WmsOutBillDetailAppDTO command = invocation.getArgument(0);
			postings.incrementAndGet();
			db.update("INSERT INTO posting VALUES (?,?)", command.getNoticeDetailId(), command.getMatNum());
			if (failPosting) {
				throw new CheckedException("库存写入失败");
			}
			return "OUT-001";
		});
		var target = new WmsMobileOutboundReceiptService(receipts, notices, details, stocks, permissions, new ObjectMapper(), mock(WmsWarehouseBusinessGuard.class));
		var proxy = new ProxyFactory(target);
		proxy.setProxyTargetClass(true);
		proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
				new AnnotationTransactionAttributeSource()));
		service = (WmsMobileOutboundReceiptService) proxy.getProxy();
		scope("operator", "F1", 1L);
	}

	@AfterEach
	void cleanup() {
		clear();
		ReflectionTestUtils.setField(SpringUtil.class, "beanFactory", previousBeanFactory);
		ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", previousApplicationContext);
		if (applicationContext != null) {
			applicationContext.close();
		}
	}

	private void clear() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
		SecurityContextHolder.clearContext();
	}

	@Test
	void mobileDisabledNewRequestCannotReserveOrPost() {
		var guard = (WmsWarehouseBusinessGuard) ReflectionTestUtils.getField(service, "warehouseGuard");
		doThrow(new CheckedException("mobile disabled")).when(guard).checkMobile("W1");

		var error = assertThrows(CheckedException.class, () -> service.issue(command()));

		assertEquals("mobile disabled", error.getMessage());
		verify(receipts, never()).reserve(any());
	}

	@Test
	void disablingMobileDoesNotPreventOriginalReplayOrSafeCancellation() {
		var original = service.issue(command());
		var guard = (WmsWarehouseBusinessGuard) ReflectionTestUtils.getField(service, "warehouseGuard");
		doThrow(new CheckedException("mobile disabled")).when(guard).checkMobile("W1");

		assertEquals(original, service.issue(command()));
		assertEquals(original, service.cancel(command(), "核对原结果"));
	}

	@Test
	void disabledMobileStillAllowsUnknownOriginalToBeSafelyCancelled() {
		var guard = (WmsWarehouseBusinessGuard) ReflectionTestUtils.getField(service, "warehouseGuard");
		doThrow(new CheckedException("mobile disabled")).when(guard).checkMobile("W1");

		assertEquals("CANCELLED", service.cancel(command(), "关闭后安全终止原请求").status());
		verify(guard, never()).checkMobile(anyString());
	}

	@Test
	void sameRequestReturnsTheOriginalReceiptWithoutReposting() {
		var first = service.issue(command());
		var second = service.issue(command());

		assertEquals("CONFIRMED", first.status());
		assertEquals(first, second);
		assertEquals(1, postings.get());
		assertEquals(1, db.queryForObject("SELECT count(*) FROM posting", Integer.class));
	}

	@Test
	void changedPayloadCannotReuseAnExistingRequest() {
		service.issue(command());
		Command changed = command();
		changed.issue().setMatNum(3D).setOperateNum(3D);

		assertThrows(CheckedException.class, () -> service.issue(changed));
		assertEquals(1, postings.get());
	}

	@Test
	void postingFailureRollsBackBothRegistrationAndReceipt() {
		failPosting = true;

		assertThrows(CheckedException.class, () -> service.issue(command()));
		assertEquals(0, db.queryForObject("SELECT count(*) FROM posting", Integer.class));
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_wms.wms_mobile_outbound_receipt", Integer.class));
		assertEquals("NOT_CONFIRMED", service.lookup(REQUEST, 100L, "W1").status());
	}

	@Test
	void permanentCancellationPreventsAnyLaterPostingOfTheOriginalRequest() {
		var cancelled = service.cancel(command(), "重新核对数量");

		assertEquals("CANCELLED", cancelled.status());
		assertEquals("CANCELLED", service.issue(command()).status());
		assertEquals(0, postings.get());
	}

	@Test
	void cancellationOfAnAlreadyConfirmedReceiptReturnsTheOriginalSuccess() {
		var original = service.issue(command());

		assertEquals(original, service.cancel(command(), "核对原结果"));
		assertEquals(1, postings.get());
	}

	@Test
	void receiptReplayRequiresTheOriginalFactoryActorWarehouseAndPermission() {
		service.cancel(command(), "测试撤销");
		scope("another", "F1", 1L);
		assertThrows(CheckedException.class, () -> service.lookup(REQUEST, 100L, "W1"));
		scope("operator", "F2", 1L);
		assertThrows(CheckedException.class, () -> service.lookup(REQUEST, 100L, "W1"));
		scope("operator", "F1", 1L);
		assertThrows(CheckedException.class, () -> service.lookup(REQUEST, 100L, "W2"));
		when(permissions.hasPermission("wms_notice_inout_other_out_W1")).thenReturn(false);
		assertThrows(CheckedException.class, () -> service.lookup(REQUEST, 100L, "W1"));
	}

	@Test
	void concurrentSameRequestPostsOnceAndBothCallersReadTheSameResult() throws Exception {
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		doAnswer(invocation -> {
			postings.incrementAndGet();
			entered.countDown();
			assertTrue(release.await(4, TimeUnit.SECONDS));
			db.update("INSERT INTO posting VALUES (200,2)");
			return "OUT-001";
		}).when(handler).setOutBillNum(any());
		var secondReserving = observeSecondReservation();
		var executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(this::receiveInWorker);
			assertTrue(entered.await(4, TimeUnit.SECONDS));
			var second = executor.submit(this::receiveInWorker);
			assertTrue(secondReserving.await(4, TimeUnit.SECONDS));
			release.countDown();

			assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
			assertEquals(1, postings.get());
		} finally {
			release.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void receiptConfirmationFailureRollsBackTheSuccessfulBusinessWrite() {
		doReturn(0).when(receipts).complete(anyLong(), anyString(), anyString(), anyString(),
				anyString(), anyString(), anyString(), any(), isNull());

		assertThrows(CheckedException.class, () -> service.issue(command()));

		assertEquals(1, postings.get());
		assertEquals(0, db.queryForObject("SELECT count(*) FROM posting", Integer.class));
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_wms.wms_mobile_outbound_receipt", Integer.class));
	}

	@Test
	void changedStandardUnitIsRejectedBeforePosting() {
		Command changed = command();
		changed.issue().setOperateUnit("BOX");

		assertThrows(CheckedException.class, () -> service.issue(changed));

		assertEquals(0, postings.get());
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_wms.wms_mobile_outbound_receipt", Integer.class));
	}

	@Test
	void tenantAndSkippedContextsCannotReadOrReplayAnotherTenantReceipt() {
		service.cancel(command(), "核对收货");
		scope("operator", "F1", 2L);
		assertThrows(CheckedException.class, () -> service.lookup(REQUEST, 100L, "W1"));
		scope("operator", "F1", 1L);
		FactoryContextHolder.setFactorySkip();
		assertThrows(CheckedException.class, () -> service.issue(command()));
		FactoryContextHolder.clear();
		FactoryContextHolder.setFactoryCode("F1");
		TenantContextHolder.setTenantSkip();
		assertThrows(CheckedException.class, () -> service.context());
	}

	@Test
	void confirmedReceiptRemainsReplayableAfterTheOriginalNoticeIsFinishedOrRemoved() {
		var confirmed = service.issue(command());
		when(notices.selectById(100L)).thenReturn(null);
		when(stocks.selectById(300L)).thenReturn(null);

		assertEquals(confirmed, service.issue(command()));
		assertEquals(confirmed, service.lookup(REQUEST, 100L, "W1"));
		assertEquals(1, postings.get());
	}

	@Test
	void cancellationRacingAnExecutingRequestReturnsTheCommittedReceipt() throws Exception {
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		doAnswer(invocation -> {
			postings.incrementAndGet();
			entered.countDown();
			assertTrue(release.await(4, TimeUnit.SECONDS));
			db.update("INSERT INTO posting VALUES (200,2)");
			return "OUT-001";
		}).when(handler).setOutBillNum(any());
		var secondReserving = observeSecondReservation();
		var executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(this::receiveInWorker);
			assertTrue(entered.await(4, TimeUnit.SECONDS));
			var second = executor.submit(() -> {
				scope("operator", "F1", 1L);
				try {
					return service.cancel(command(), "核对原请求");
				} finally {
					clear();
				}
			});
			assertTrue(secondReserving.await(4, TimeUnit.SECONDS));
			release.countDown();

			assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
			assertEquals(1, db.queryForObject("SELECT count(*) FROM posting", Integer.class));
		} finally {
			release.countDown();
			executor.shutdownNow();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"produce", "sales", "other_out", "other_retail", "other_outsource", "other_pms"})
	void everyOutboundTypeUsesItsOwnNoticeAndPermissionForPermanentReceipts(String type) {
		notice.setBillType(type);
		when(handler.support(type)).thenReturn(true);
		when(permissions.hasPermission("wms_notice_inout_" + (type.startsWith("other_") ? "other_out" : type) + "_W1")).thenReturn(true);
		Command request = new Command(REQUEST, "W1", type, command().issue());

		var confirmed = service.issue(request);

		assertEquals("CONFIRMED", confirmed.status());
		assertEquals(confirmed, service.issue(request));
		assertEquals(confirmed, service.lookup(REQUEST, 100L, "W1"));
		assertEquals(type, receipts.find(1L, REQUEST).billType());
		assertEquals(1, postings.get());
	}

	@Test
	void otherOutboundPermissionDoesNotGrantSalesShipping() {
		notice.setBillType("sales");
		Command request = new Command(REQUEST, "W1", "sales", command().issue());

		assertThrows(CheckedException.class, () -> service.issue(request));

		assertEquals(0, postings.get());
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_wms.wms_mobile_outbound_receipt", Integer.class));
	}

	@Test
	void sameRequestCannotSubstituteTheOriginalStockIdentity() {
		service.issue(command());
		Command changed = command();
		changed.issue().setSourceStockId(301L);

		assertThrows(CheckedException.class, () -> service.issue(changed));

		assertEquals(1, postings.get());
	}

	@Test
	void previewCannotExposeStockWithoutTheActualWarehouseOperationPermission() {
		when(permissions.hasPermission("wms_notice_inout_other_out_W1")).thenReturn(false);

		assertThrows(CheckedException.class, () -> service.sources(100L, "W1", "other_out"));
		assertThrows(CheckedException.class, () -> service.sources(100L, "W2", "other_out"));

		assertEquals(0, postings.get());
	}

	@Test
	void changedStockBarcodeCannotBeUsedByTheOriginalCommand() {
		stocks.selectById(300L).setMatBarcode("CHANGED-SERIAL");

		assertThrows(CheckedException.class, () -> service.issue(command()));

		assertEquals(0, postings.get());
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_wms.wms_mobile_outbound_receipt", Integer.class));
	}

	@Test
	void previewRechecksTheReturnedWarehouseBeforeExposingRecommendedStock() {
		var sourceHandler = mock(AbstractWmsOutNoticeHandler.class);
		when(sourceHandler.support("other_retail")).thenReturn(true);
		var changed = new WmsOutBillEntity();
		changed.setNoticeId(100L);
		changed.setTenantId(1L);
		changed.setFactoryCode("F1");
		changed.setWarehouseCode("W2");
		changed.setBillType("other_retail");
		when(sourceHandler.getLiveStockByNoticeIdApp(100L)).thenReturn(changed);
		applicationContext.getBeanFactory().registerSingleton("outboundSource", sourceHandler);

		assertThrows(CheckedException.class, () -> service.sources(100L, "W1", "other_out"));
	}

	@Test
	void legacyEndpointAlsoRequiresActualWarehousePermissionBeforeAnyWrite() {
		when(permissions.hasPermission("wms_notice_inout_other_out_W1")).thenReturn(false);

		var controller = new AppOutBillController(service);

		assertThrows(CheckedException.class, () -> controller.setOutBillNum(command().issue()));

		assertEquals(0, db.queryForObject("SELECT count(*) FROM posting", Integer.class));
	}

	@Test
	void legacyEndpointDispatchesTheActualBusinessHandlerWithinItsAuthorizedTransaction() {
		notice.setBillType("produce");
		when(permissions.hasPermission("wms_notice_inout_produce_W1")).thenReturn(true);
		when(handler.support("produce")).thenReturn(true);
		var controller = new AppOutBillController(service);

		assertEquals("OUT-001", controller.setOutBillNum(command().issue()).getData());

		assertEquals(1, db.queryForObject("SELECT count(*) FROM posting", Integer.class));
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_wms.wms_mobile_outbound_receipt", Integer.class));
	}

	/**
	 * 第二个事务进入真实唯一键插入后才释放首个事务，避免只验证顺序重放。
	 */
	private CountDownLatch observeSecondReservation() {
		var second = new CountDownLatch(1);
		var attempts = new AtomicInteger();
		doAnswer(invocation -> {
			if (attempts.incrementAndGet() == 2) {
				second.countDown();
			}
			return sqlReceipts.reserve(invocation.getArgument(0));
		}).when(receipts).reserve(any());
		return second;
	}

	private Object receiveInWorker() {
		scope("operator", "F1", 1L);
		try {
			return service.issue(command());
		} finally {
			clear();
		}
	}

	private static final String REQUEST = "mobile-receipt-00000001";

	private Command command() {
		return new Command(REQUEST, "W1", "other_retail", new WmsOutBillDetailAppDTO().setNoticeId(100L)
				.setNoticeDetailId(200L).setSourceStockId(300L).setMatCode("M1").setMatBatch("B1").setSiteCode("S1")
				.setMatNum(2D).setOperateNum(2D).setOperateUnit("EA"));
	}

	private void scope(String username, String factory, Long tenant) {
		TenantContextHolder.setTenantId(tenant);
		FactoryContextHolder.setFactoryCode(factory);
		MomUser actor = mock(MomUser.class);
		when(actor.getUsername()).thenReturn(username);
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null));
	}
}
