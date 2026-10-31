package com.kitex.mom.wms.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.wms.dto.WmsProductionReturnReceiptCommand;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.enums.menu.ReturnReceiptActionEnum;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.WmsReturnReceiptAccessService;
import com.kitex.mom.wms.service.inBill.service.WmsProductionReturnInBillService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 调用真实处置/续跑服务；只替换持久化与Outbox边界。 */
class WmsProductionReturnActionReplayTest {
	private WmsProductionReturnReceiptService service;
	private WmsInNoticeEntity notice;
	private WmsMesMaterialReturnReceiptOutboxService outbox;
	private WmsProductionReturnInBillService inbound;
	private WmsReturnReceiptAccessService access;
	private WmsInNoticeMapper notices;
	private final Map<String, WmsProductionReturnReceiptOperationEntity> rows = new LinkedHashMap<>();
	private WmsMesMaterialReturnReceiptOutboxEntity delivery;
	private static final String REQUEST = "return-disposition-000001";

	@BeforeEach
	void setup() {
		for (var type : List.of(WmsInNoticeEntity.class, WmsInBillEntity.class, WmsInBillDetailEntity.class,
				WmsProductionReturnReceiptOperationEntity.class, WmsMesMaterialReturnReceiptOutboxEntity.class)) {
			TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "action-replay"), type);
		}
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
		actor("reviewer");
		rows.clear();
		notice = new WmsInNoticeEntity();
		notice.setId(100L);
		notice.setTenantId(1L);
		notice.setFactoryCode("F1");
		notice.setWarehouseCode("W1");
		notice.setBillType("return");
		notice.setDocumentTable("mom_mes.mes_warehouse_stock_feed_judge");
		notice.setNoticeInNum(10D);
		notice.setState("2");
		notice.setReturnReceiptStatus("VARIANCE_PENDING");
		notice.setReturnShortageNum(new BigDecimal("6"));
		notice.setReturnOverageNum(BigDecimal.ZERO);
		notice.setReturnReporterCode("receiver");
		notices = mock(WmsInNoticeMapper.class);
		when(notices.selectOne(any(Wrapper.class))).thenAnswer(c -> notice);
		when(notices.selectById(100L)).thenAnswer(c -> notice);
		when(notices.updateById(any(WmsInNoticeEntity.class))).thenReturn(1);
		var bills = mock(WmsInBillMapper.class);
		when(bills.selectList(any(Wrapper.class))).thenReturn(List.of());
		var details = mock(WmsInBillDetailMapper.class);
		var operations = mock(WmsProductionReturnReceiptOperationMapper.class);
		when(operations.selectOne(any(Wrapper.class))).thenAnswer(c -> {
			var wrapper = (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) c.getArgument(0);
			wrapper.getCustomSqlSegment();
			return wrapper.getParamNameValuePairs().values().stream().filter(v -> v instanceof String)
					.map(v -> rows.get(v.toString())).filter(Objects::nonNull).findFirst().orElse(null);
		});
		when(operations.selectList(any(Wrapper.class))).thenAnswer(c -> new ArrayList<>(rows.values()));
		when(operations.insert(any(WmsProductionReturnReceiptOperationEntity.class))).thenAnswer(c -> {
			WmsProductionReturnReceiptOperationEntity row = c.getArgument(0);
			// 实际MyBatis租户填充在测试适配器中模拟。
			if (row.getTenantId() == null) row.setTenantId(TenantContextHolder.getTenantId());
			row.setId((long) rows.size() + 1);
			assertNull(rows.putIfAbsent(row.getRequestId(), row));
			return 1;
		});
		var deliveries = mock(WmsMesMaterialReturnReceiptOutboxMapper.class);
		delivery = new WmsMesMaterialReturnReceiptOutboxEntity().setEventId("event-1").setStatus("PENDING");
		when(deliveries.selectOne(any(Wrapper.class))).thenAnswer(c -> delivery);
		outbox = mock(WmsMesMaterialReturnReceiptOutboxService.class);
		when(outbox.resetNotice(100L)).thenReturn(2);
		inbound = mock(WmsProductionReturnInBillService.class);
		access = mock(WmsReturnReceiptAccessService.class);
		when(access.capabilities(any())).thenReturn(Map.of("dispose", true, "retry", true));
		service = new WmsProductionReturnReceiptService(notices, bills, details, operations, deliveries,
				inbound, outbox, access);
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
		FactoryContextHolder.clear();
		TenantContextHolder.clear();
	}

	private void actor(String username) {
		var actor = mock(MomUser.class);
		when(actor.getUsername()).thenReturn(username);
		when(actor.getName()).thenReturn(username);
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, "N/A", List.of()));
	}

	private WmsProductionReturnReceiptCommand.Disposition command() {
		return new WmsProductionReturnReceiptCommand.Disposition().setNoticeId(100L).setRequestId(REQUEST)
				.setDecision("CONTINUE_RECEIPT").setReason("现场复核继续收货");
	}
	private WmsProductionReturnReceiptCommand.RetryDelivery retry() {
		return new WmsProductionReturnReceiptCommand.RetryDelivery().setNoticeId(100L)
				.setRequestId("return-delivery-retry-000001").setReason("回写故障已恢复");
	}

	@Test
	void changedDecisionOrReasonCannotReuseDispositionRequest() {
		service.dispose(command());
		assertThrows(CheckedException.class, () -> service.dispose(command().setDecision("ACCEPT_SHORTAGE")));
		assertThrows(CheckedException.class, () -> service.dispose(command().setReason("不同依据")));
		verify(notices, times(1)).updateById(any(WmsInNoticeEntity.class));
		verify(outbox, times(1)).enqueueNoticeState(any(), eq(REQUEST));
	}

	@Test
	void anotherOperatorCannotReplayDispositionOrDeliveryRequest() {
		service.dispose(command());
		service.retryDelivery(retry());
		actor("other-reviewer");
		assertThrows(CheckedException.class, () -> service.dispose(command()));
		assertThrows(CheckedException.class, () -> service.retryDelivery(retry()));
		verify(outbox, times(1)).resetNotice(100L);
	}

	@Test
	void anotherTenantOrFactoryAuditCannotBeReplayed() {
		service.dispose(command());
		var row = rows.get(REQUEST);
		row.setTenantId(2L);
		assertThrows(CheckedException.class, () -> service.dispose(command()));
		row.setTenantId(1L);
		row.setFactoryCode("F2");
		assertThrows(CheckedException.class, () -> service.dispose(command()));
	}

	@Test
	void sameDispositionReplaysOriginalResultAfterLaterBusinessChanges() {
		service.dispose(command());
		notice.setReturnReceiptStatus("VARIANCE_PENDING");
		notice.setReturnDisposition("ACCEPT_SHORTAGE");
		var original = service.dispose(command());
		assertEquals("RECEIVING", original.getNotice().getReturnReceiptStatus());
		assertEquals("CONTINUE_RECEIPT", original.getNotice().getReturnDisposition());
		verify(notices, times(1)).updateById(any(WmsInNoticeEntity.class));
		assertTrue(original.getOperations() == null || original.getOperations().isEmpty());
	}

	@Test
	void changedDeliveryReasonCannotResetEventsAgainAndOriginalOutcomeIsFrozen() {
		service.retryDelivery(retry());
		delivery.setStatus("DEAD");
		assertThrows(CheckedException.class, () -> service.retryDelivery(retry().setReason("另一次故障")));
		var original = service.retryDelivery(retry());
		assertEquals("PENDING", original.getDeliveryStatus());
		verify(outbox, times(1)).resetNotice(100L);
		verifyZeroInteractions(inbound);
	}

	@Test
	void replayUsesCurrentPermissionFlagsWithoutRequiringEnabledBusinessAgain() {
		service.dispose(command());
		doThrow(new CheckedException("仓库业务已停用")).when(access).requireEnabled(any(), any());
		when(access.capabilities(any())).thenReturn(Map.of("dispose", false));
		var original = service.dispose(command());
		assertEquals(Map.of("dispose", false), original.getCapabilities());
		verify(access, times(1)).requireEnabled(notice, ReturnReceiptActionEnum.DISPOSE);
	}

	@Test
	void corruptOrHistoricalIncompleteOutcomeCannotBeInventedFromCurrentNotice() {
		service.dispose(command());
		var row = rows.get(REQUEST);
		row.setDetail("CONTINUE_RECEIPT：现场复核继续收货");
		assertThrows(CheckedException.class, () -> service.dispose(command()));
		row.setDetail("{broken-json");
		assertThrows(CheckedException.class, () -> service.dispose(command()));
		verify(notices, times(1)).updateById(any(WmsInNoticeEntity.class));
	}

	@Test
	void newAuditContainsCanonicalOriginalAndDoesNotNestEarlierHistory() {
		service.dispose(command().setReason("  现场复核继续收货  "));
		var document = JSONUtil.parseObj(rows.get(REQUEST).getDetail());
		assertEquals(1, document.getInt("contractVersion"));
		assertEquals("现场复核继续收货", document.getJSONObject("command").getStr("reason"));
		assertTrue(document.getJSONObject("result").getJSONArray("operations").isEmpty());
		assertEquals(1L, rows.get(REQUEST).getTenantId());
		assertDoesNotThrow(() -> service.dispose(command()));
	}
	@Test
	void finalizationAuditDoesNotRecursivelyEmbedEarlierActionSnapshots() {
		service.dispose(command());
		notice.setReturnReceiptStatus("RECEIVING");
		notice.setState("1");
		service.finalizeReceipt(new WmsProductionReturnReceiptCommand.Finalize().setNoticeId(100L)
				.setRequestId("return-finalize-after-000001").setReason("最终收货数量核对"));
		var snapshot = JSONUtil.parseObj(rows.get("return-finalize-after-000001").getDetail());
		assertTrue(snapshot.getJSONArray("operations").isEmpty());
	}

	@Test
	void incompleteReturnStateCannotResetDeliveryBeforeAUsableAuditCanBeWritten() {
		notice.setReturnReceiptStatus(null);
		assertThrows(CheckedException.class, () -> service.retryDelivery(retry()));
		verify(outbox, never()).resetNotice(anyLong());
	}

	@Test
	void parsedSnapshotWithMissingBusinessFieldsCannotBeReportedAsOriginalSuccess() {
		service.dispose(command());
		var row = rows.get(REQUEST);
		String original = row.getDetail();
		for (String field : List.of("expectedQuantity", "actualReceivedQuantity", "shortageQuantity", "overageQuantity", "hasOpenBill")) {
			for (boolean explicitNull : List.of(false, true)) {
				var snapshot = JSONUtil.parseObj(original);
				var result = snapshot.getJSONObject("result");
				if (explicitNull) {
					result.set(field, cn.hutool.json.JSONNull.NULL);
				} else {
					result.remove(field);
				}
				row.setDetail(snapshot.toString());
				assertThrows(CheckedException.class, () -> service.dispose(command()));
			}
		}
		verify(notices, times(1)).updateById(any(WmsInNoticeEntity.class));
	}

	@Test
	void legacyActionEndpointsCannotExecuteAnOriginalRequestSealedAsCancelled() {
		for (String action : List.of("DISPOSITION", "DELIVERY_RETRY")) {
			String requestId = "DISPOSITION".equals(action) ? REQUEST : retry().getRequestId();
			String decision = "DISPOSITION".equals(action) ? command().getDecision() : "";
			String reason = "DISPOSITION".equals(action) ? command().getReason() : retry().getReason();
			var original = com.kitex.mom.wms.service.WmsReturnActionSnapshot.command(notice, action, decision, reason);
			var row = new WmsProductionReturnReceiptOperationEntity().setNoticeId(100L).setRequestId(requestId)
					.setFactoryCode("F1").setOperatorCode("reviewer").setOperationType(action)
					.setDetail(JSONUtil.createObj().set("contractVersion", 1).set("command", original)
							.set("status", "CANCELLED").set("result", cn.hutool.json.JSONNull.NULL)
							.set("cancellationReason", "核对原请求").toString());
			row.setTenantId(1L);
			rows.put(requestId, row);
		}
		assertThrows(CheckedException.class, () -> service.dispose(command()));
		assertThrows(CheckedException.class, () -> service.retryDelivery(retry()));
		verify(notices, never()).updateById(any(WmsInNoticeEntity.class));
		verifyZeroInteractions(outbox, inbound);
	}

	@Test
	void inconsistentOrUntypedFrozenQuantitiesCannotBeReplayed() {
		service.dispose(command());
		var row = rows.get(REQUEST);
		String original = row.getDetail();
		for (Object quantity : List.of(-1, "0", 99)) {
			var snapshot = JSONUtil.parseObj(original);
			snapshot.getJSONObject("result").set("shortageQuantity", quantity);
			row.setDetail(snapshot.toString());
			assertThrows(CheckedException.class, () -> service.dispose(command()));
		}
	}

}
