package com.kitex.mom.wms.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.qms.api.feign.RemoteQmsFinishedGoodsDecisionReversalService;
import com.kitex.mom.qms.dto.QmsFinishedGoodsDecisionReversalDTO;
import com.kitex.mom.wms.dto.WmsFinishedGoodsReceiptCorrectionCommand;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.impl.WmsFinishedGoodsReceiptCorrectionServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 实际申请/冻结/履历服务与Spring事务，持久边界为合成H2适配；不运行QMS或实际库存红冲。
 */
class WmsFinishedGoodsCorrectionMobileApplyTest {
	private JdbcTemplate db;
	private WmsFinishedGoodsReceiptCorrectionService service;
	private WmsWarehouseBusinessGuard guard;
	private PermissionService permissions;
	private RemoteQmsFinishedGoodsDecisionReversalService quality;
	private List<WmsLiveStockEntity> stockRows;
	private String failStage;

	@BeforeEach
	void setup() {
		var source = new DriverManagerDataSource("jdbc:h2:mem:mobile-fg-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
		db = new JdbcTemplate(source);
		for (String table : List.of("application", "frozen", "audit")) {
			db.execute("create table " + table + " (id bigint, body varchar(20000))");
		}
		for (Class<?> type : List.of(WmsFinishedGoodsReceiptCorrectionEntity.class,
				WmsInNoticeEntity.class, WmsInNoticeDetailEntity.class, WmsInBillEntity.class,
				WmsInBillDetailEntity.class, WmsLiveStockEntity.class, WmsInoutFlowEntity.class,
				WmsFinishedGoodsReceiptCorrectionDetailEntity.class, WmsFinishedGoodsReceiptCorrectionOperationEntity.class)) {
			TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "fg-mobile"), type);
		}
		var headers = mock(WmsFinishedGoodsReceiptCorrectionMapper.class);
		when(headers.selectOne(any(Wrapper.class), eq(false))).thenAnswer(call -> {
			List<WmsFinishedGoodsReceiptCorrectionEntity> rows = db.query("select body from application", (rs, i) -> JSONUtil.toBean(rs.getString(1), WmsFinishedGoodsReceiptCorrectionEntity.class));
			return rows.isEmpty() ? null : rows.get(0);
		});
		when(headers.insert(any(WmsFinishedGoodsReceiptCorrectionEntity.class))).thenAnswer(call -> {
			WmsFinishedGoodsReceiptCorrectionEntity row = call.getArgument(0);
			row.setId(2000L);
			row.setTenantId(1L);
			row.setDelFlag("0");
			return db.update("insert into application values(?,?)", row.getId(), JSONUtil.toJsonStr(row));
		});
		var details = mock(WmsFinishedGoodsReceiptCorrectionDetailMapper.class);
		when(details.insert(any(WmsFinishedGoodsReceiptCorrectionDetailEntity.class))).thenAnswer(call -> {
			if ("second-line".equals(failStage) && count("frozen") == 1) {
				return 0;
			}
			WmsFinishedGoodsReceiptCorrectionDetailEntity row = call.getArgument(0);
			row.setTenantId(1L);
			row.setDelFlag("0");
			return db.update("insert into frozen values(?,?)", row.getStockId(), JSONUtil.toJsonStr(row));
		});
		when(details.selectList(any())).thenAnswer(call -> db.query("select body from frozen", (rs, i) -> JSONUtil.toBean(rs.getString(1), WmsFinishedGoodsReceiptCorrectionDetailEntity.class)));
		var operations = mock(WmsFinishedGoodsReceiptCorrectionOperationMapper.class);
		when(operations.insert(any(WmsFinishedGoodsReceiptCorrectionOperationEntity.class))).thenAnswer(call -> {
			if ("audit".equals(failStage)) {
				return 0;
			}
			WmsFinishedGoodsReceiptCorrectionOperationEntity row = call.getArgument(0);
			return db.update("insert into audit values(1,?)", JSONUtil.toJsonStr(row));
		});
		var notice = new WmsInNoticeEntity();
		notice.setId(100L);
		notice.setTenantId(1L);
		notice.setDelFlag("0");
		notice.setFactoryCode("F1");
		notice.setWarehouseCode("W1");
		notice.setBillType("product");
		notice.setState("2");
		notice.setSourceRequestId("MES_TASK_IN_WMS:source-1");
		notice.setSourceRequestFingerprint("a".repeat(64));
		notice.setNoticeInNum(5d);
		notice.setAllInNum(5d);
		notice.setAllNotInNum(0d);
		var notices = mock(WmsInNoticeMapper.class);
		when(notices.selectOne(any(Wrapper.class), eq(false))).thenReturn(notice);
		var noticeLines = mock(WmsInNoticeDetailMapper.class);
		var noticeLine = new WmsInNoticeDetailEntity();
		noticeLine.setId(101L);
		noticeLine.setNoticeInNum(5d);
		when(noticeLines.selectList(any())).thenReturn(List.of(noticeLine));
		var bills = mock(WmsInBillMapper.class);
		var bill = new WmsInBillEntity();
		bill.setId(200L);
		bill.setState("2");
		when(bills.selectList(any())).thenReturn(List.of(bill));
		var billLines = mock(WmsInBillDetailMapper.class);
		var lines = new ArrayList<WmsInBillDetailEntity>();
		stockRows = new ArrayList<>();
		var flows = new ArrayList<WmsInoutFlowEntity>();
		for (long index = 0; index < 2; index++) {
			double amount = index == 0 ? 2d : 3d;
			var line = new WmsInBillDetailEntity();
			line.setId(300L + index);
			line.setBillId(200L);
			line.setMatCode("M" + index);
			line.setMatBatch("B1");
			line.setMatUnit("PCS");
			line.setMatNum(amount);
			line.setSourceQualityTaskIds("[1]");
			line.setSourceQualityEvidenceFingerprint("b".repeat(64));
			lines.add(line);
			var stock = new WmsLiveStockEntity();
			stock.setId(400L + index);
			stock.setTenantId(1L);
			stock.setFactoryCode("F1");
			stock.setDelFlag("0");
			stock.setWarehouseCode("W1");
			stock.setSiteCode("S1");
			stock.setSourceMesInboundRequestId("source-1");
			stock.setSourceInBillId(200L);
			stock.setSourceInBillDetailId(line.getId());
			stock.setMatCode(line.getMatCode());
			stock.setMatBatch("B1");
			stock.setMatUnit("PCS");
			stock.setMatNum(amount);
			stock.setStatus(0);
			stock.setStockOwnerType("OWNED");
			stock.setSourceQualityTaskIds("[1]");
			stock.setSourceQualityEvidenceFingerprint("b".repeat(64));
			stock.setSourceQualityEvidenceSnapshot("{}");
			stockRows.add(stock);
			var flow = new WmsInoutFlowEntity();
			flow.setId(500L + index);
			flow.setBillDetailId(line.getId());
			flow.setSourceInBillId(200L);
			flow.setSourceInBillDetailId(line.getId());
			flow.setMatNum(amount);
			flows.add(flow);
		}
		when(billLines.selectList(any())).thenReturn(lines);
		var stocks = mock(WmsLiveStockMapper.class);
		when(stocks.selectList(any())).thenAnswer(call -> stockRows);
		var flowMapper = mock(WmsInoutFlowMapper.class);
		when(flowMapper.selectList(any())).thenReturn(flows);
		var productionReserved = mock(WmsMaterialReservationService.class);
		when(productionReserved.activeReservedNum(anyLong())).thenReturn(BigDecimal.ZERO);
		var salesReserved = mock(WmsSalesStockReservationService.class);
		when(salesReserved.activeReservedNums(any())).thenReturn(Map.of());
		permissions = mock(PermissionService.class);
		when(permissions.hasPermission("wms_bill_add_in_product_W1")).thenReturn(true);
		guard = mock(WmsWarehouseBusinessGuard.class);
		quality = mock(RemoteQmsFinishedGoodsDecisionReversalService.class);
		var authorization = new QmsFinishedGoodsDecisionReversalDTO.Authorization();
		authorization.setEligible(true);
		authorization.setReversalId(5L);
		authorization.setAuthorizationCode("fixture-only");
		authorization.setQmsEvidenceFingerprint("b".repeat(64));
		when(quality.consume(any())).thenReturn(R.ok(authorization));
		var mobile = new WmsFinishedGoodsCorrectionMobileAccess(notices, stocks, permissions, guard);
		var target = new WmsFinishedGoodsReceiptCorrectionServiceImpl(details, permissions, operations,
				notices, noticeLines, bills, billLines, stocks, flowMapper, productionReserved, salesReserved,
				mock(WmsLiveStockService.class), mock(WmsFinishedGoodsReceiptReverseOutboxService.class),
				mock(WmsQmsFinishedGoodsDecisionReversalOutboxService.class), quality, mobile);
		ReflectionTestUtils.setField(target, "baseMapper", headers);
		var proxy = new ProxyFactory(target);
		proxy.setProxyTargetClass(true);
		proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
		service = (WmsFinishedGoodsReceiptCorrectionService) proxy.getProxy();
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
		var actor = mock(MomUser.class);
		when(actor.getUsername()).thenReturn("operator");
		when(actor.getTenantId()).thenReturn(1L);
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, "N/A", List.of()));
	}

	@AfterEach
	void clear() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
		SecurityContextHolder.clearContext();
	}

	@Test
	void disabledMobileApplicationCannotConsumeQualityAuthorizationOrFreezeAnyRows() {
		doThrow(new CheckedException("mobile disabled")).when(guard).checkMobile("W1");
		assertEquals("mobile disabled", assertThrows(CheckedException.class, () -> service.applyMobile(command())).getMessage());
		unchanged();
		verify(quality, never()).consume(any());
	}

	@Test
	void unpermittedOrMovedStockCannotBeRegisteredUnderTheOriginalWarehouse() {
		stockRows.get(1).setWarehouseCode("W2");
		assertThrows(CheckedException.class, () -> service.applyMobile(command()));
		stockRows.get(1).setWarehouseCode("W1");
		when(permissions.hasPermission("wms_bill_add_in_product_W1")).thenReturn(false);
		assertThrows(CheckedException.class, () -> service.applyMobile(command()));
		unchanged();
		verify(quality, never()).consume(any());
		verifyZeroInteractions(guard);
	}

	@Test
	void unauthorizedOriginalWarehouseIsRejectedBeforeReportingSelectedStockAvailability() {
		when(permissions.hasPermission("wms_bill_add_in_product_W1")).thenReturn(false);
		var command = command();
		command.getStockSelections().get(0).setQuantity(new BigDecimal("100"));

		assertEquals("当前账号无权提交该仓库成品纠错申请", assertThrows(CheckedException.class,
				() -> service.applyMobile(command)).getMessage());
		unchanged();
		verify(quality, never()).consume(any());
	}

	@Test
	void movedStockCannotDiscloseItsAvailabilityBeforeWarehouseMismatchIsRejected() {
		stockRows.get(1).setWarehouseCode("W2");
		var command = command();
		command.getStockSelections().get(1).setQuantity(new BigDecimal("100"));

		assertTrue(assertThrows(CheckedException.class, () -> service.applyMobile(command))
				.getMessage().contains("移出原收货仓库"));
		unchanged();
		verify(quality, never()).consume(any());
	}

	@Test
	void movedAndUnavailableStockIsRejectedBeforeGlobalAvailabilityBlockersAreDisclosed() {
		for (var row : stockRows) {
			row.setWarehouseCode("W2");
			row.setStatus(1);
		}

		assertTrue(assertThrows(CheckedException.class, () -> service.applyMobile(command()))
				.getMessage().contains("移出原收货仓库"));
		unchanged();
		verify(quality, never()).consume(any());
		verifyZeroInteractions(guard);
	}

	@Test
	void registeredMobileApplicationFreezesExactQuantityAndReplaysAfterMobileCloses() {
		var first = service.applyMobile(command());
		doThrow(new CheckedException("mobile disabled")).when(guard).checkMobile("W1");
		clearInvocations(guard, quality);
		assertEquals(first.getId(), service.applyMobile(command()).getId());
		assertEquals(1, count("application"));
		assertEquals(2, count("frozen"));
		assertEquals(1, count("audit"));
		assertEquals(0, new BigDecimal("5").compareTo(first.getTotalQuantity()));
		var frozenQuantity = db.query("select body from frozen", (rs, i) ->
						JSONUtil.toBean(rs.getString(1), WmsFinishedGoodsReceiptCorrectionDetailEntity.class).getQuantity())
				.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
		assertEquals(0, new BigDecimal("5").compareTo(frozenQuantity));
		verifyZeroInteractions(guard, quality);
	}

	@Test
	void pcApplicationKeepsItsOriginalContractWhenMobileIsDisabled() {
		doThrow(new CheckedException("mobile disabled")).when(guard).checkMobile("W1");
		assertNotNull(service.apply(command()).getId());
		verifyZeroInteractions(guard);
	}

	@Test
	void secondFrozenLineFailureRollsBackHeaderAndFirstFrozenLineLocally() {
		failStage = "second-line";
		assertThrows(CheckedException.class, () -> service.applyMobile(command()));
		unchanged();
		// 远程授权不在本地事务内，不能将本地回滚误报为跨服务原子回滚。
		verify(quality).consume(any());
	}

	@Test
	void auditFailureRollsBackEveryLocalApplicationRecord() {
		failStage = "audit";
		assertThrows(CheckedException.class, () -> service.applyMobile(command()));
		unchanged();
	}

	@Test
	void originalMobileReplayRequiresFrozenWarehousePermissionWithoutCurrentSourceReads() {
		service.applyMobile(command());
		stockRows.clear();
		when(permissions.hasPermission("wms_bill_add_in_product_W1")).thenReturn(false);
		assertThrows(CheckedException.class, () -> service.applyMobile(command()));
		assertEquals(1, count("application"));
		assertEquals(2, count("frozen"));
	}

	private WmsFinishedGoodsReceiptCorrectionCommand command() {
		var command = new WmsFinishedGoodsReceiptCorrectionCommand();
		command.setNoticeId(100L);
		command.setRequestId("FG-MOBILE-ORIGINAL-001");
		command.setReason("现场批次核对");
		command.setQmsDecisionReversalId(5L);
		command.setQmsDecisionAuthorizationCode("fixture-only");
		command.setStockSelections(stockSelections());
		return command;
	}

	private List<WmsFinishedGoodsReceiptCorrectionCommand.StockSelection> stockSelections() {
		var result = new ArrayList<WmsFinishedGoodsReceiptCorrectionCommand.StockSelection>();
		for (long index = 0; index < 2; index++) {
			var line = new WmsFinishedGoodsReceiptCorrectionCommand.StockSelection();
			line.setStockId(400L + index);
			line.setQuantity(new BigDecimal(index == 0 ? "2" : "3"));
			result.add(line);
		}
		return result;
	}

	private int count(String table) {
		return db.queryForObject("select count(*) from " + table, Integer.class);
	}

	private void unchanged() {
		assertEquals(0, count("application"));
		assertEquals(0, count("frozen"));
		assertEquals(0, count("audit"));
	}
}
