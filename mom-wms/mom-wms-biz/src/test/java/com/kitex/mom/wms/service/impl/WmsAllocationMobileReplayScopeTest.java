package com.kitex.mom.wms.service.impl;

import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.dto.app.WmsAllocationBillAppDTO;
import com.kitex.mom.wms.entity.WmsAllocationBillEntity;
import com.kitex.mom.wms.mapper.WmsAllocationBillMapper;
import com.kitex.mom.wms.mapper.WmsAllocationMobileCancelReceiptMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WmsAllocationMobileReplayScopeTest {
	private WmsAllocationBillServiceImpl service;
	private RemoteWarehouseService warehouses;
	private WmsAllocationBillEntity original;

	@BeforeEach
	void setup() {
		service = mock(WmsAllocationBillServiceImpl.class, CALLS_REAL_METHODS);
		warehouses = mock(RemoteWarehouseService.class);
		var permissions = mock(PermissionService.class);
		var mapper = mock(WmsAllocationBillMapper.class);
		ReflectionTestUtils.setField(service, "remoteWarehouseService", warehouses);
		ReflectionTestUtils.setField(service, "permissionService", permissions);
		ReflectionTestUtils.setField(service, "baseMapper", mapper);
		ReflectionTestUtils.setField(service, "mobileCancelReceipts", mock(WmsAllocationMobileCancelReceiptMapper.class));
		when(permissions.hasPermission(anyString(), anyString())).thenReturn(true);
		when(mapper.lockAgentTransfer(anyString())).thenReturn(1);
		when(warehouses.getByCode(anyString())).thenAnswer(call -> R.ok(new MdmWarehouseEntity()
				.setWarehouseCode(call.getArgument(0)).setFactoryCode("F1").setStatus(true)));
		identity("operator");
		String snapshot = ReflectionTestUtils.invokeMethod(service, "appRequestSnapshot", command());
		original = new WmsAllocationBillEntity().setFactoryCode("F1").setRequestId("request-1").setRequestSnapshot(snapshot)
				.setBillNo("TR-1").setOldWarehouseCode("W1").setNewWarehouseCode("W2").setIssueBy("operator")
				.setIssueTime(LocalDateTime.now()).setFinishTime(LocalDateTime.now()).setSourceDeducted(true)
				.setReceiveRequestId("request-1").setReceiveRequestSnapshot(snapshot).setAllocationState("RECEIVED");
		original.setId(7L);
		original.setTenantId(1L);
		doAnswer(call -> original).when(service).getOne(any(Wrapper.class), eq(false));
	}

	private void identity(String actor) {
		FactoryContextHolder.setFactoryCode("F1");
		TenantContextHolder.setTenantId(1L);
		var user = mock(MomUser.class);
		when(user.getUsername()).thenReturn(actor);
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
	}

	@AfterEach
	void clear() {
		FactoryContextHolder.clear();
		TenantContextHolder.clear();
		SecurityContextHolder.clearContext();
	}

	private WmsAllocationBillAppDTO command() {
		var command = new WmsAllocationBillAppDTO();
		command.setRequestId("request-1");
		command.setBillNo("TR-1");
		command.setFactoryCode("F1");
		command.setOldWarehouseCode("W1");
		command.setNewWarehouseCode("W2");
		command.setOldSiteCode("S1");
		command.setNewSiteCode("S2");
		command.setMatCode("M1");
		command.setMatUnit("EA");
		command.setOperateNum(2D);
		return command;
	}

	@Test
	void newMobileTransferRequiresBothWarehousesToBeEnabled() {
		original = null;
		var guard = mock(WmsWarehouseBusinessGuard.class);
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", guard);
		doThrow(new CheckedException("target mobile disabled")).when(guard).checkMobile("W2");

		var error = assertThrows(CheckedException.class, () -> service.appSaveAllocationBill(command()));

		assertEquals("target mobile disabled", error.getMessage());
		verify(guard).checkMobile("W1");
		verify(warehouses, never()).getByCode(anyString());
	}

	@Test
	void committedMobileTransferCanReplayAfterTheSwitchIsClosed() {
		var guard = mock(WmsWarehouseBusinessGuard.class);
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", guard);
		doThrow(new CheckedException("mobile disabled")).when(guard).checkMobile(anyString());

		assertTrue(service.appSaveAllocationBill(command()));
		verify(guard, never()).checkMobile(anyString());
	}

	@Test
	void originalReplayDoesNotNeedLiveWarehouseConfiguration() {
		when(warehouses.getByCode(anyString())).thenThrow(new CheckedException("仓库已停用"));
		assertTrue(service.appSaveAllocationBill(command()));
		verifyZeroInteractions(warehouses);
	}

	@Test
	void anotherOperatorCannotClaimAnOriginalMobileRequest() {
		identity("other");
		assertThrows(CheckedException.class, () -> service.appSaveAllocationBill(command()));
	}

	@Test
	void missingFactoryAndSkippedTenantCannotUseClientFactoryFallback() {
		FactoryContextHolder.clear();
		assertThrows(CheckedException.class, () -> service.appSaveAllocationBill(command()));
		identity("operator");
		TenantContextHolder.setTenantSkip();
		assertThrows(CheckedException.class, () -> service.appSaveAllocationBill(command()));
	}

	@Test
	void nonFiniteQuantityIsRejectedBeforeAnyWarehouseRead() {
		for (double quantity : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
			var command = command();
			command.setOperateNum(quantity);
			assertThrows(CheckedException.class, () -> service.appSaveAllocationBill(command));
		}
		verifyZeroInteractions(warehouses);
	}
}
