package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.wms.dto.app.WmsMobileFinishedGoodsCorrectionDTO.ContextQuery;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.mapper.WmsInNoticeMapper;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WmsFinishedGoodsCorrectionMobileAccessTest {
	private WmsFinishedGoodsCorrectionMobileAccess access;
	private WmsWarehouseBusinessGuard guard;
	private PermissionService permissions;
	private WmsInNoticeEntity notice;
	private WmsLiveStockEntity stock;
	private MomUser actor;

	@BeforeEach
	void setup() {
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
		actor = mock(MomUser.class);
		when(actor.getUsername()).thenReturn("operator");
		when(actor.getTenantId()).thenReturn(1L);
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, "N/A", List.of()));
		var notices = mock(WmsInNoticeMapper.class);
		var stocks = mock(WmsLiveStockMapper.class);
		permissions = mock(PermissionService.class);
		guard = mock(WmsWarehouseBusinessGuard.class);
		when(permissions.hasPermission("wms_bill_add_in_product_W1")).thenReturn(true);
		notice = new WmsInNoticeEntity();
		notice.setId(100L);
		notice.setTenantId(1L);
		notice.setFactoryCode("F1");
		notice.setDelFlag("0");
		notice.setBillType("product");
		notice.setWarehouseCode("W1");
		notice.setSourceRequestId("MES_TASK_IN_WMS:source-1");
		stock = new WmsLiveStockEntity();
		stock.setId(400L);
		stock.setTenantId(1L);
		stock.setFactoryCode("F1");
		stock.setDelFlag("0");
		stock.setWarehouseCode("W1");
		stock.setSourceMesInboundRequestId("source-1");
		when(notices.selectOne(any(Wrapper.class), eq(false))).thenReturn(notice);
		when(stocks.selectList(any())).thenReturn(List.of(stock));
		access = new WmsFinishedGoodsCorrectionMobileAccess(notices, stocks, permissions, guard);
	}

	@AfterEach
	void clear() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
		SecurityContextHolder.clearContext();
	}

	@Test
	void contextBindsActualIdentityNoticeWarehouseAndExactStockIdentifiers() {
		when(guard.mobileEnabled("W1")).thenReturn(true);
		var result = access.context(query());

		assertEquals(1, result.contractVersion());
		assertEquals(1, result.mobileContractVersion());
		assertEquals("1", result.tenantId());
		assertEquals("F1", result.factoryCode());
		assertEquals("operator", result.operatorCode());
		assertEquals("100", result.noticeId());
		assertEquals("W1", result.warehouseCode());
		assertEquals(List.of("400"), result.stockIds());
		assertTrue(result.mobileEnabled());
		when(guard.mobileEnabled("W1")).thenReturn(false);
		assertFalse(access.context(query()).mobileEnabled());
	}

	@Test
	void onlyConfigurationProbeFailureBecomesDisabledWhilePermissionFailurePropagates() {
		when(guard.mobileEnabled("W1")).thenThrow(new IllegalStateException("catalog unavailable"));
		assertFalse(access.context(query()).mobileEnabled());
		when(permissions.hasPermission("wms_bill_add_in_product_W1")).thenReturn(false);
		assertThrows(CheckedException.class, () -> access.context(query()));
	}

	@Test
	void movedOrForeignSourceStockCannotBecomeASingleWarehouseApplication() {
		stock.setWarehouseCode("W2");
		assertThrows(CheckedException.class, () -> access.context(query()));
		assertThrows(CheckedException.class, () -> access.requireNew(notice, List.of(stock)));
		stock.setWarehouseCode("W1");
		stock.setSourceMesInboundRequestId("another-source");
		assertThrows(CheckedException.class, () -> access.context(query()));
		stock.setSourceMesInboundRequestId("source-1");
		stock.setFactoryCode("F2");
		assertThrows(CheckedException.class, () -> access.context(query()));
		stock.setFactoryCode("F1");
		stock.setTenantId(2L);
		assertThrows(CheckedException.class, () -> access.context(query()));

		verifyZeroInteractions(guard);
	}

	@Test
	void missingRepeatedOrUnrelatedRowsCannotPassTheContextContract() {
		assertThrows(CheckedException.class, () -> access.context(new ContextQuery(100L, "W1", List.of(401L))));
		assertThrows(CheckedException.class, () -> access.context(new ContextQuery(100L, "W1", List.of(400L, 400L))));
		assertThrows(CheckedException.class, () -> access.context(new ContextQuery(100L, "W1", List.of())));
		assertThrows(CheckedException.class, () -> access.context(new ContextQuery(100L, "W*", List.of(400L))));
		verifyZeroInteractions(guard);
	}

	@Test
	void skippedOrMismatchedRealIdentityFailsOutsideCapabilityFallback() {
		when(actor.getTenantId()).thenReturn(2L);
		assertThrows(CheckedException.class, () -> access.context(query()));
		when(actor.getTenantId()).thenReturn(1L);
		FactoryContextHolder.setFactorySkip();
		assertThrows(CheckedException.class, () -> access.context(query()));
		verifyZeroInteractions(guard);
	}

	@Test
	void newApplicationGateDoesNotSwallowMobileConfigurationFailure() {
		doThrow(new CheckedException("mobile disabled")).when(guard).checkMobile("W1");
		assertEquals("mobile disabled", assertThrows(CheckedException.class,
				() -> access.requireNew(notice, List.of(stock))).getMessage());
	}

	private ContextQuery query() {
		return new ContextQuery(100L, "W1", List.of(400L));
	}
}
