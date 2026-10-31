package com.kitex.mom.wms.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.wms.dto.WmsReturnActionDTO;
import com.kitex.mom.wms.dto.WmsProductionReturnReceiptCommand;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.entity.WmsProductionReturnReceiptOperationEntity;
import com.kitex.mom.wms.enums.menu.ReturnReceiptActionEnum;
import com.kitex.mom.wms.mapper.WmsInNoticeMapper;
import com.kitex.mom.wms.mapper.WmsProductionReturnReceiptOperationMapper;
import com.kitex.mom.wms.service.impl.WmsProductionReturnReceiptService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** JDBC唯一键、原通知行锁及真实事务，业务边界写入同一数据源以核对回滚。 */
class WmsReturnActionRecoveryTest {
	private static final String REQUEST = "return-action-recovery-000001";
	private JdbcTemplate db;
	private WmsReturnActionService service;
	private PermissionService permissions;
	private boolean rejectAudit;
	private final AtomicLong ids = new AtomicLong(1000);

	@BeforeEach
	void setup() {
		var source = new DriverManagerDataSource("jdbc:h2:mem:action-" + UUID.randomUUID()
				+ ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
		source.setDriverClassName("org.h2.Driver");
		db = new JdbcTemplate(source);
		db.execute("create table notice(id bigint primary key, tenant_id bigint, factory_code varchar, warehouse_code varchar)");
		db.execute("create table operation(tenant_id bigint, request_id varchar, notice_id bigint, payload clob, unique(tenant_id, request_id))");
		db.execute("create table effect(request_id varchar, kind varchar)");
		db.update("insert into notice values(100, 1,'F1','W1'),(200, 1,'F2','W2')");
		for (Class<?> type : List.of(WmsInNoticeEntity.class, WmsProductionReturnReceiptOperationEntity.class)) {
			TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "action-recovery"), type);
		}
		var notices = mock(WmsInNoticeMapper.class);
		when(notices.selectOne(any(Wrapper.class))).thenAnswer(c -> {
			Bound sql = bind("select * from notice " + ((Wrapper<?>) c.getArgument(0)).getCustomSqlSegment(), c.getArgument(0));
			var found = db.query(sql.sql(), (r, n) -> notice(r.getLong("id"), r.getLong("tenant_id"),
					r.getString("factory_code"), r.getString("warehouse_code")), sql.values());
			return found.isEmpty() ? null : found.get(0);
		});
		var operations = mock(WmsProductionReturnReceiptOperationMapper.class);
		when(operations.selectOne(any(Wrapper.class))).thenAnswer(c -> {
			Bound sql = bind("select payload from operation " + ((Wrapper<?>) c.getArgument(0)).getCustomSqlSegment(), c.getArgument(0));
			var found = db.query(sql.sql(), (r, n) -> JSONUtil.toBean(r.getString(1), WmsProductionReturnReceiptOperationEntity.class), sql.values());
			return found.isEmpty() ? null : found.get(0);
		});
		when(operations.insert(any(WmsProductionReturnReceiptOperationEntity.class))).thenAnswer(c -> {
			if (rejectAudit) {
				return 0;
			}
			WmsProductionReturnReceiptOperationEntity row = c.getArgument(0);
			row.setId(ids.incrementAndGet());
			return db.update("insert into operation values(?,?,?,?)", row.getTenantId(), row.getRequestId(), row.getNoticeId(), JSONUtil.toJsonStr(row));
		});
		var business = mock(WmsProductionReturnReceiptService.class);
		when(business.dispose(any())).thenAnswer(c -> {
			WmsProductionReturnReceiptCommand.Disposition command = c.getArgument(0);
			return post(operations, command.getRequestId(), "DISPOSITION", command.getDecision(), command.getReason());
		});
		when(business.retryDelivery(any())).thenAnswer(c -> {
			WmsProductionReturnReceiptCommand.RetryDelivery command = c.getArgument(0);
			return post(operations, command.getRequestId(), "DELIVERY_RETRY", "", command.getReason());
		});
		permissions = mock(PermissionService.class);
		when(permissions.hasPermission("wms_return_receipt_dispose_W1")).thenReturn(true);
		when(permissions.hasPermission("wms_return_receipt_retry_W1")).thenReturn(true);
		var guard = mock(WmsWarehouseBusinessGuard.class);
		when(guard.returnReceiptActions("W1")).thenReturn(EnumSet.allOf(ReturnReceiptActionEnum.class));
		var access = new WmsReturnReceiptAccessService(permissions, guard);
		var target = new WmsReturnActionService(notices, operations, business, access);
		var proxy = new ProxyFactory(target);
		proxy.setProxyTargetClass(true);
		proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
		service = (WmsReturnActionService) proxy.getProxy();
		identity("reviewer", "F1", 1L);
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	private WmsInNoticeEntity notice(long id, long tenant, String factory, String warehouse) {
		var row = new WmsInNoticeEntity();
		row.setId(id);
		row.setTenantId(tenant);
		row.setFactoryCode(factory);
		row.setWarehouseCode(warehouse);
		row.setBillType("return");
		row.setDocumentTable("mom_mes.mes_warehouse_stock_feed_judge");
		row.setNoticeInNum(10D);
		row.setReturnOverageNum(BigDecimal.ZERO);
		row.setReturnReceiptStatus("VARIANCE_PENDING");
		return row;
	}

	private WmsProductionReturnReceiptCommand.View post(WmsProductionReturnReceiptOperationMapper operations,
			String request, String kind, String decision, String reason) {
		db.update("insert into effect values(?,?)", request, kind);
		var original = notice(100, 1, "F1", "W1");
		original.setReturnReceiptStatus("DISPOSITION".equals(kind) ? "RECEIVING" : "VARIANCE_PENDING");
		original.setReturnDisposition(decision);
		var result = new WmsProductionReturnReceiptCommand.View().setNotice(original).setExpectedQuantity(BigDecimal.TEN)
				.setActualReceivedQuantity(new BigDecimal("4")).setShortageQuantity(new BigDecimal("6"))
				.setOverageQuantity(BigDecimal.ZERO).setHasOpenBill(false).setOperations(List.of());
		var row = new WmsProductionReturnReceiptOperationEntity().setNoticeId(100L).setRequestId(request)
				.setOperationType(kind).setFactoryCode("F1").setOperatorCode("reviewer").setOperatorName("复核员")
				.setOperationTime(LocalDateTime.now()).setBeforeStatus("VARIANCE_PENDING").setAfterStatus(original.getReturnReceiptStatus())
				.setDetail(WmsReturnActionSnapshot.freeze(WmsReturnActionSnapshot.command(original, kind, decision, reason), result,
						"DELIVERY_RETRY".equals(kind) ? 2 : 0));
		row.setTenantId(1L);
		if (operations.insert(row) != 1) {
			throw new CheckedException("封存原操作失败");
		}
		return result;
	}

	private void identity(String operator, String factory, long tenant) {
		TenantContextHolder.setTenantId(tenant);
		FactoryContextHolder.setFactoryCode(factory);
		var user = mock(MomUser.class);
		when(user.getUsername()).thenReturn(operator);
		when(user.getName()).thenReturn(operator);
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, "N/A", List.of()));
	}

	private WmsReturnActionDTO.Command command(String kind) {
		return new WmsReturnActionDTO.Command(REQUEST, 100L, "W1", kind,
				"DISPOSITION".equals(kind) ? "CONTINUE_RECEIPT" : "", "原操作依据");
	}

	private int effects() {
		return db.queryForObject("select count(*) from effect", Integer.class);
	}

	private record Bound(String sql, Object[] values) {
	}

	private Bound bind(String sql, Wrapper<?> wrapper) {
		var matcher = Pattern.compile("#\\{ew\\.paramNameValuePairs\\.(\\w+)(?:,[^}]*)?}").matcher(sql);
		var values = new ArrayList<Object>();
		var output = new StringBuilder();
		var params = ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
		while (matcher.find()) {
			values.add(params.get(matcher.group(1)));
			matcher.appendReplacement(output, "?");
		}
		matcher.appendTail(output);
		return new Bound(output.toString(), values.toArray());
	}

	@Test
	void eachActionReturnsItsOriginalReceiptWithoutExecutingTwice() {
		for (String action : List.of("DISPOSITION", "DELIVERY_RETRY")) {
			db.update("delete from effect");
			db.update("delete from operation");
			var first = service.execute(command(action));
			assertEquals("CONFIRMED", first.status());
			assertEquals(first.requestPayload(), service.execute(command(action)).requestPayload());
			var recovered = service.lookup(REQUEST, 100L, "W1", action);
			assertEquals(first.result().getNotice().getReturnReceiptStatus(), recovered.result().getNotice().getReturnReceiptStatus());
			assertEquals(1, effects());
		}
	}

	@Test
	void cancellationBeforeExecutionSealsOriginalAndCannotExecuteLateRetry() {
		var original = command("DISPOSITION");
		var cancelled = service.cancel(new WmsReturnActionDTO.Cancellation(original, "重新核对"));
		assertEquals("CANCELLED", cancelled.status());
		assertNull(cancelled.result());
		assertEquals("CANCELLED", service.execute(original).status());
		assertEquals("CANCELLED", service.lookup(REQUEST, 100L, "W1", "DISPOSITION").status());
		assertEquals(0, effects());
	}

	@Test
	void cancellationAfterConfirmedExecutionReturnsConfirmedAndDoesNotUndoBusiness() {
		var original = command("DELIVERY_RETRY");
		service.execute(original);
		assertEquals("CONFIRMED", service.cancel(new WmsReturnActionDTO.Cancellation(original, "核对结果")).status());
		assertEquals(1, effects());
	}

	@Test
	void originalKindDecisionReasonWarehouseAndOperatorCannotBeChanged() {
		var original = command("DISPOSITION");
		service.execute(original);
		assertThrows(CheckedException.class, () -> service.execute(command("DELIVERY_RETRY")));
		assertThrows(CheckedException.class, () -> service.execute(new WmsReturnActionDTO.Command(REQUEST, 100L, "W1", "DISPOSITION", "ACCEPT_SHORTAGE", "原操作依据")));
		assertThrows(CheckedException.class, () -> service.execute(new WmsReturnActionDTO.Command(REQUEST, 100L, "W1", "DISPOSITION", "CONTINUE_RECEIPT", "不同依据")));
		assertThrows(RuntimeException.class, () -> service.lookup(REQUEST, 100L, "W2", "DISPOSITION"));
		identity("other-reviewer", "F1", 1L);
		assertThrows(CheckedException.class, () -> service.lookup(REQUEST, 100L, "W1", "DISPOSITION"));
		assertEquals(1, effects());
	}

	@Test
	void notConfirmedLookupDoesNotGrantPermissionOrProduceAnyEffect() {
		assertEquals("NOT_CONFIRMED", service.lookup(REQUEST, 100L, "W1", "DISPOSITION").status());
		assertEquals(0, effects());
		when(permissions.hasPermission("wms_return_receipt_dispose_W1")).thenReturn(false);
		assertThrows(AccessDeniedException.class, () -> service.lookup(REQUEST, 100L, "W1", "DISPOSITION"));
	}

	@Test
	void failedAuditSealingRollsBackRealEffectAndAllowsOriginalRetry() {
		rejectAudit = true;
		assertThrows(CheckedException.class, () -> service.execute(command("DISPOSITION")));
		assertEquals(0, effects());
		rejectAudit = false;
		assertEquals("CONFIRMED", service.execute(command("DISPOSITION")).status());
		assertEquals(1, effects());
	}

	@Test
	void cancellationAndExecutionCompetitionHaveOnlyOnePermanentOutcome() throws Exception {
		var pool = Executors.newFixedThreadPool(2);
		var start = new CountDownLatch(1);
		try {
			var execute = pool.submit(() -> {
				identity("reviewer", "F1", 1L);
				start.await();
				return service.execute(command("DISPOSITION")).status();
			});
			var cancel = pool.submit(() -> {
				identity("reviewer", "F1", 1L);
				start.await();
				return service.cancel(new WmsReturnActionDTO.Cancellation(command("DISPOSITION"), "重新核对")).status();
			});
			start.countDown();
			assertEquals(execute.get(10,TimeUnit.SECONDS), cancel.get(10,TimeUnit.SECONDS));
			assertTrue(effects() <= 1);
			assertEquals(1, db.queryForObject("select count(*) from operation",Integer.class));
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void invalidActionCannotBeDowngradedToAnotherCapability() {
		assertThrows(CheckedException.class, () -> service.lookup(REQUEST, 100L, "W1", " DISPOSITION "));
		assertThrows(CheckedException.class, () -> service.lookup(REQUEST, 100L, "W1", null));
		assertEquals(0, effects());
	}

	@Test
	void crossFactoryTenantAndSkippedContextCannotReadOrCancelOriginal() {
		service.execute(command("DISPOSITION"));
		identity("reviewer", "F2", 1L);
		assertThrows(RuntimeException.class, () -> service.lookup(REQUEST, 100L, "W1", "DISPOSITION"));
		identity("reviewer", "F1", 2L);
		assertThrows(RuntimeException.class, () -> service.lookup(REQUEST, 100L, "W1", "DISPOSITION"));
		identity("reviewer", "F1", 1L);
		FactoryContextHolder.setFactorySkip();
		assertThrows(AccessDeniedException.class, () -> service.cancel(new WmsReturnActionDTO.Cancellation(command("DISPOSITION"), "核对原请求")));
		assertEquals(1, effects());
	}

	@Test
	void cancellationWriteFailureCannotCreateAFalseReceipt() {
		rejectAudit = true;
		assertThrows(CheckedException.class, () -> service.cancel(new WmsReturnActionDTO.Cancellation(command("DISPOSITION"), "重新核对")));
		assertEquals("NOT_CONFIRMED", service.lookup(REQUEST, 100L, "W1", "DISPOSITION").status());
		assertEquals(0, effects());
	}

}
