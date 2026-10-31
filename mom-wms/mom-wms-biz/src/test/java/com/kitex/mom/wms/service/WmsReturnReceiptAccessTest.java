package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.enums.menu.ReturnReceiptActionEnum;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WmsReturnReceiptAccessTest {
	private WmsReturnReceiptAccessService access;
	private WmsWarehouseBusinessGuard guard;
	private WmsInNoticeEntity notice;

	@BeforeEach
	void setup() {
		FactoryContextHolder.setFactoryCode("F1");
		TenantContextHolder.setTenantId(1L);
		guard = mock(WmsWarehouseBusinessGuard.class);
		when(guard.returnReceiptActions("W1")).thenReturn(EnumSet.allOf(ReturnReceiptActionEnum.class));
		access = new WmsReturnReceiptAccessService(new PermissionService(), guard);
		notice = new WmsInNoticeEntity();
		notice.setTenantId(1L);
		notice.setFactoryCode("F1");
		notice.setWarehouseCode("W1");
		notice.setBillType("return");
		notice.setDocumentTable("mom_mes.mes_warehouse_stock_feed_judge");
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
		FactoryContextHolder.clear();
		TenantContextHolder.clear();
	}

	private void login(String... permissions) {
		var user = mock(MomUser.class);
		when(user.getUsername()).thenReturn("receiver");
		var authorities = Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList();
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(user, "unused", authorities));
	}

	@Test
	void scopedActionDoesNotGrantAnotherWarehouseOrAction() {
		login("wms_return_receipt_finalize_W1");
		assertDoesNotThrow(() -> access.requirePermission("W1", ReturnReceiptActionEnum.FINALIZE));
		assertThrows(AccessDeniedException.class, () -> access.requirePermission("W2", ReturnReceiptActionEnum.FINALIZE));
		assertThrows(AccessDeniedException.class, () -> access.requirePermission("W1", ReturnReceiptActionEnum.DISPOSE));
	}

	@Test
	void legacyGlobalActionRequiresActualWarehouseAccess() {
		login("wms_return_receipt_finalize", "wms_notice_view_return_W2");
		assertThrows(AccessDeniedException.class, () -> access.requirePermission("W1", ReturnReceiptActionEnum.FINALIZE));
		login("wms_return_receipt_finalize", "wms_notice_view_return_W1");
		assertDoesNotThrow(() -> access.requirePermission("W1", ReturnReceiptActionEnum.FINALIZE));
	}

	@Test
	void capabilitiesCombineConfigurationAndAuthorizationWithoutGrantingWrites() {
		login("wms_return_receipt_view_W1", "wms_return_receipt_finalize_W1");
		when(guard.returnReceiptActions("W1")).thenReturn(EnumSet.of(ReturnReceiptActionEnum.VIEW));
		var result = access.capabilities(notice);
		assertTrue(result.get("view"));
		assertFalse(result.get("finalize"));
		assertFalse(result.get("dispose"));
		assertThrows(CheckedException.class, () -> access.requireEnabled(notice, ReturnReceiptActionEnum.FINALIZE));
		assertDoesNotThrow(() -> access.requirePermission("W1", ReturnReceiptActionEnum.FINALIZE));
	}

	@Test
	void stoppedWarehouseKeepsReceiptPermissionButCannotPostAgain() {
		login("wms_return_receipt_finalize_W1");
		when(guard.returnReceiptActions("W1")).thenThrow(new CheckedException("仓库未启用"));
		assertDoesNotThrow(() -> access.requirePermission("W1", ReturnReceiptActionEnum.FINALIZE));
		assertFalse(access.capabilities(notice).get("finalize"));
		assertThrows(CheckedException.class, () -> access.requireEnabled(notice, ReturnReceiptActionEnum.FINALIZE));
	}

	@Test
	void realScopeRequiredEvenWhenPermissionMatches() {
		login("wms_return_receipt_finalize_W1");
		notice.setFactoryCode("F2");
		assertThrows(AccessDeniedException.class, () -> access.requireNotice(notice, ReturnReceiptActionEnum.FINALIZE));
		notice.setFactoryCode("F1");
		notice.setTenantId(2L);
		assertThrows(AccessDeniedException.class, () -> access.requireNotice(notice, ReturnReceiptActionEnum.FINALIZE));
		assertThrows(AccessDeniedException.class, () -> access.requirePermission("W*", ReturnReceiptActionEnum.FINALIZE));
		SecurityContextHolder.clearContext();
		assertThrows(AccessDeniedException.class, () -> access.requirePermission("W1", ReturnReceiptActionEnum.FINALIZE));
	}
}
