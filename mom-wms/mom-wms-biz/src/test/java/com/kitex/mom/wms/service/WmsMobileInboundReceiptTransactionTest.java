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
import com.kitex.mom.wms.dto.app.WmsInBillDetailAppDTO;
import com.kitex.mom.wms.dto.app.WmsMobileInboundReceiptDTO.Command;
import com.kitex.mom.wms.entity.WmsInNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.mapper.WmsInNoticeDetailMapper;
import com.kitex.mom.wms.mapper.WmsInNoticeMapper;
import com.kitex.mom.wms.mapper.WmsMobileInboundReceiptMapper;
import com.kitex.mom.wms.service.inBill.AbstractWmsInBillHandler;
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

class WmsMobileInboundReceiptTransactionTest {
	private JdbcTemplate db;
	private WmsMobileInboundReceiptService service;
	private WmsMobileInboundReceiptMapper receipts;
	private WmsMobileInboundReceiptMapper sqlReceipts;
	private WmsInNoticeMapper notices;
	private WmsInNoticeDetailMapper details;
	private PermissionService permissions;
	private AbstractWmsInBillHandler handler;
	private Object previousBeanFactory;
	private Object previousApplicationContext;
	private StaticApplicationContext applicationContext;
	private WmsInNoticeEntity notice;
	private final AtomicInteger postings = new AtomicInteger();
	private boolean failPosting;

	@BeforeEach
	void setup() throws Exception {
		var source = new DriverManagerDataSource("jdbc:h2:mem:inbound-receipt-" + UUID.randomUUID()
				+ ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(source);
		db.execute("CREATE SCHEMA mom_wms");
		String migration = Files.readString(Path.of("../database/wms-mobile-inbound-receipt-pg.sql"));
		int start = migration.indexOf("CREATE TABLE");
		db.execute(migration.substring(start, migration.indexOf(';', start)));
		db.execute("CREATE TABLE posting (request_source bigint, quantity numeric)");
		var configuration = new MybatisConfiguration();
		configuration.addMapper(WmsMobileInboundReceiptMapper.class);
		var factory = new MybatisSqlSessionFactoryBean();
		factory.setDataSource(source);
		factory.setConfiguration(configuration);
		sqlReceipts = new SqlSessionTemplate(factory.getObject()).getMapper(WmsMobileInboundReceiptMapper.class);
		receipts = mock(WmsMobileInboundReceiptMapper.class, org.mockito.AdditionalAnswers.delegatesTo(sqlReceipts));
		notices = mock(WmsInNoticeMapper.class);
		details = mock(WmsInNoticeDetailMapper.class);
		permissions = mock(PermissionService.class);
		when(permissions.hasPermission("wms_notice_inout_other_in_W1")).thenReturn(true);
		notice = new WmsInNoticeEntity();
		notice.setId(100L);
		notice.setTenantId(1L);
		notice.setFactoryCode("F1");
		notice.setWarehouseCode("W1");
		notice.setBillType("other_in");
		when(notices.selectById(100L)).thenReturn(notice);
		when(notices.lockNotice(100L)).thenReturn(100L);
		var detail = new WmsInNoticeDetailEntity();
		detail.setId(200L);
		detail.setNoticeId(100L);
		detail.setTenantId(1L);
		detail.setFactoryCode("F1");
		detail.setWarehouseCode("W1");
		detail.setMatCode("M1");
		detail.setMatUnit("EA");
		when(details.selectById(200L)).thenReturn(detail);
		handler = mock(AbstractWmsInBillHandler.class);
		when(handler.support("other_in")).thenReturn(true);
		previousBeanFactory = ReflectionTestUtils.getField(SpringUtil.class, "beanFactory");
		previousApplicationContext = SpringUtil.getApplicationContext();
		applicationContext = new StaticApplicationContext();
		applicationContext.getBeanFactory().registerSingleton("inbound", handler);
		new SpringUtil().postProcessBeanFactory(applicationContext.getBeanFactory());
		new SpringUtil().setApplicationContext(applicationContext);
		when(handler.setInBillNum(any())).thenAnswer(invocation -> {
			assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
			WmsInBillDetailAppDTO command = invocation.getArgument(0);
			postings.incrementAndGet();
			db.update("INSERT INTO posting VALUES (?,?)", command.getNoticeDetailId(), command.getMatNum());
			if (failPosting) {
				throw new CheckedException("库存写入失败");
			}
			return "IN-001";
		});
		var target = new WmsMobileInboundReceiptService(receipts, notices, details, permissions, new ObjectMapper());
		var proxy = new ProxyFactory(target);
		proxy.setProxyTargetClass(true);
		proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
				new AnnotationTransactionAttributeSource()));
		service = (WmsMobileInboundReceiptService) proxy.getProxy();
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
	void sameRequestReturnsTheOriginalReceiptWithoutReposting() {
		var first = service.receive(command());
		var second = service.receive(command());

		assertEquals("CONFIRMED", first.status());
		assertEquals(first, second);
		assertEquals(1, postings.get());
		assertEquals(1, db.queryForObject("SELECT count(*) FROM posting", Integer.class));
	}

	@Test
	void changedPayloadCannotReuseAnExistingRequest() {
		service.receive(command());
		Command changed = command();
		changed.receipt().setMatNum(3D).setOperateNum(3D);

		assertThrows(CheckedException.class, () -> service.receive(changed));
		assertEquals(1, postings.get());
	}

	@Test
	void postingFailureRollsBackBothRegistrationAndReceipt() {
		failPosting = true;

		assertThrows(CheckedException.class, () -> service.receive(command()));
		assertEquals(0, db.queryForObject("SELECT count(*) FROM posting", Integer.class));
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_wms.wms_mobile_inbound_receipt", Integer.class));
		assertEquals("NOT_CONFIRMED", service.lookup(REQUEST, 100L, "W1").status());
	}

	@Test
	void permanentCancellationPreventsAnyLaterPostingOfTheOriginalRequest() {
		var cancelled = service.cancel(command(), "重新核对数量");

		assertEquals("CANCELLED", cancelled.status());
		assertEquals("CANCELLED", service.receive(command()).status());
		assertEquals(0, postings.get());
	}

	@Test
	void cancellationOfAnAlreadyConfirmedReceiptReturnsTheOriginalSuccess() {
		var original = service.receive(command());

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
		when(permissions.hasPermission("wms_notice_inout_other_in_W1")).thenReturn(false);
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
			return "IN-001";
		}).when(handler).setInBillNum(any());
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

		assertThrows(CheckedException.class, () -> service.receive(command()));

		assertEquals(1, postings.get());
		assertEquals(0, db.queryForObject("SELECT count(*) FROM posting", Integer.class));
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_wms.wms_mobile_inbound_receipt", Integer.class));
	}

	@Test
	void changedStandardUnitIsRejectedBeforePosting() {
		Command changed = command();
		changed.receipt().setOperateUnit("BOX");

		assertThrows(CheckedException.class, () -> service.receive(changed));

		assertEquals(0, postings.get());
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_wms.wms_mobile_inbound_receipt", Integer.class));
	}

	@Test
	void tenantAndSkippedContextsCannotReadOrReplayAnotherTenantReceipt() {
		service.cancel(command(), "核对收货");
		scope("operator", "F1", 2L);
		assertThrows(CheckedException.class, () -> service.lookup(REQUEST, 100L, "W1"));
		scope("operator", "F1", 1L);
		FactoryContextHolder.setFactorySkip();
		assertThrows(CheckedException.class, () -> service.receive(command()));
		FactoryContextHolder.clear();
		FactoryContextHolder.setFactoryCode("F1");
		TenantContextHolder.setTenantSkip();
		assertThrows(CheckedException.class, () -> service.context());
	}

	@Test
	void confirmedReceiptRemainsReplayableAfterTheOriginalNoticeIsFinishedOrRemoved() {
		var confirmed = service.receive(command());
		when(notices.selectById(100L)).thenReturn(null);

		assertEquals(confirmed, service.receive(command()));
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
			return "IN-001";
		}).when(handler).setInBillNum(any());
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
	@ValueSource(strings = {"purchase", "return", "product", "sales_return", "other_in"})
	void everyInboundTypeUsesItsOwnNoticeAndPermissionForPermanentReceipts(String type) {
		notice.setBillType(type);
		when(handler.support(type)).thenReturn(true);
		when(permissions.hasPermission("wms_notice_inout_" + type + "_W1")).thenReturn(true);
		Command request = new Command(REQUEST, "W1", type, command().receipt());

		var confirmed = service.receive(request);

		assertEquals("CONFIRMED", confirmed.status());
		assertEquals(confirmed, service.receive(request));
		assertEquals(confirmed, service.lookup(REQUEST, 100L, "W1"));
		assertEquals(type, receipts.find(1L, REQUEST).billType());
		assertEquals(1, postings.get());
	}

	@Test
	void otherInboundPermissionDoesNotGrantPurchaseReceiving() {
		notice.setBillType("purchase");
		Command request = new Command(REQUEST, "W1", "purchase", command().receipt());

		assertThrows(CheckedException.class, () -> service.receive(request));

		assertEquals(0, postings.get());
		assertEquals(0, db.queryForObject("SELECT count(*) FROM mom_wms.wms_mobile_inbound_receipt", Integer.class));
	}

	/** 第二个事务进入真实唯一键插入后才释放首个事务，避免只验证顺序重放。 */
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
			return service.receive(command());
		} finally {
			clear();
		}
	}

	private static final String REQUEST = "mobile-receipt-00000001";

	private Command command() {
		return new Command(REQUEST, "W1", "other_in", new WmsInBillDetailAppDTO().setNoticeId(100L)
				.setNoticeDetailId(200L).setMatCode("M1").setMatBatch("B1").setSiteCode("S1")
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
