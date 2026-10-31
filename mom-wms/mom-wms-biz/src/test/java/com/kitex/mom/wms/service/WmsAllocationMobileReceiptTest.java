package com.kitex.mom.wms.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.wms.entity.WmsAllocationBillEntity;
import com.kitex.mom.wms.mapper.WmsAllocationBillMapper;
import com.kitex.mom.wms.mapper.WmsAllocationMobileCancelReceiptMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WmsAllocationMobileReceiptTest {
	private WmsAllocationBillMapper bills;
	private PermissionService permissions;
	private WmsAllocationMobileReceiptService service;
	private WmsAllocationBillEntity original;

	@BeforeEach
	void setup() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "allocation-receipt"), WmsAllocationBillEntity.class);
		bills = mock(WmsAllocationBillMapper.class);
		permissions = mock(PermissionService.class);
		when(permissions.hasPermission("wms_wmsAllocationBill_add", "wms_wmsAllocationBill_add_W1")).thenReturn(true);
		service = new WmsAllocationMobileReceiptService(bills, permissions,
				mock(WmsAllocationMobileCancelReceiptMapper.class));
		identity("operator", "F1", 1L);
		String snapshot = JSONUtil.createObj().set("billNo", "TR-1").set("factory", "F1")
				.set("sourceWarehouse", "W1").set("sourceSite", "S1").set("targetWarehouse", "W2").set("targetSite", "S2")
				.set("material", "M1").set("batch", "B1").set("supplier", "SUP").set("quantity", 2).set("unit", "EA")
				.set("sourceStockId", 9007199254740993L).set("matBarcode", "SN-1").toString();
		original = new WmsAllocationBillEntity().setRequestId("allocation-original-000001").setBillNo("TR-1")
				.setFactoryCode("F1").setOldWarehouseCode("W1").setNewWarehouseCode("W2").setIssueBy("operator")
				.setIssueTime(LocalDateTime.of(2026, 10, 30, 20, 0)).setFinishTime(LocalDateTime.of(2026, 10, 30, 20, 0))
				.setAllocationState("RECEIVED").setSourceDeducted(true).setRequestSnapshot(snapshot)
				.setReceiveRequestId("allocation-original-000001").setReceiveRequestSnapshot(snapshot);
		original.setId(7L);
		original.setTenantId(1L);
		when(bills.selectOne(any(Wrapper.class))).thenAnswer(call -> original);
	}

	@AfterEach
	void clear() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
		SecurityContextHolder.clearContext();
	}

	private void identity(String username, String factory, long tenant) {
		FactoryContextHolder.setFactoryCode(factory);
		TenantContextHolder.setTenantId(tenant);
		var user = mock(MomUser.class);
		when(user.getUsername()).thenReturn(username);
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, "N/A", List.of()));
	}

	@Test
	void lookupReturnsOriginalCompletionWithoutDependingOnLiveWarehouseOrStock() {
		var first = service.lookup("allocation-original-000001", "W1");
		assertEquals("CONFIRMED", first.status());
		assertEquals("TR-1", first.billNo());
		assertEquals(2, first.quantity().intValueExact());
		assertEquals("9007199254740993", JSONUtil.parseObj(first.requestPayload()).get("sourceStockId"));
		original.setAllocationState("RETURNED");
		assertEquals(first, service.lookup("allocation-original-000001", "W1"));
		verify(bills, never()).updateById(any(WmsAllocationBillEntity.class));
	}

	@Test
	void missingOriginalIsNotConfirmedAndDoesNotCreateAnyReceipt() {
		original = null;
		var result = service.lookup("allocation-original-000001", "W1");
		assertEquals("NOT_CONFIRMED", result.status());
		assertNull(result.requestPayload());
		assertNull(result.billId());
		verify(bills, never()).insert(any(WmsAllocationBillEntity.class));
	}

	@Test
	void anotherActorTenantFactoryOrWarehouseCannotClaimTheOriginal() {
		identity("other", "F1", 1L);
		assertThrows(CheckedException.class, () -> service.lookup("allocation-original-000001", "W1"));
		identity("operator", "F2", 1L);
		assertThrows(CheckedException.class, () -> service.lookup("allocation-original-000001", "W1"));
		identity("operator", "F1", 2L);
		assertThrows(CheckedException.class, () -> service.lookup("allocation-original-000001", "W1"));
		identity("operator", "F1", 1L);
		when(permissions.hasPermission("wms_wmsAllocationBill_add", "wms_wmsAllocationBill_add_W2")).thenReturn(true);
		assertThrows(CheckedException.class, () -> service.lookup("allocation-original-000001", "W2"));
	}

	@Test
	void scopeBypassMissingIdentityAndRevokedPermissionAreRejectedBeforeRead() {
		FactoryContextHolder.setFactorySkip();
		assertThrows(CheckedException.class, () -> service.lookup("allocation-original-000001", "W1"));
		FactoryContextHolder.clear();
		assertThrows(CheckedException.class, () -> service.lookup("allocation-original-000001", "W1"));
		identity("operator", "F1", 1L);
		when(permissions.hasPermission("wms_wmsAllocationBill_add", "wms_wmsAllocationBill_add_W1")).thenReturn(false);
		assertThrows(CheckedException.class, () -> service.lookup("allocation-original-000001", "W1"));
		verifyZeroInteractions(bills);
	}

	@Test
	void incompleteCompletionMarkersOrForeignStoredSnapshotCannotBeInventedAsSuccess() {
		String snapshot = original.getRequestSnapshot();
		original.setReceiveRequestId("another-request");
		assertThrows(CheckedException.class, () -> service.lookup("allocation-original-000001", "W1"));
		original.setReceiveRequestId(original.getRequestId()).setFinishTime(null);
		assertThrows(CheckedException.class, () -> service.lookup("allocation-original-000001", "W1"));
		original.setFinishTime(original.getIssueTime());
		String changed = JSONUtil.parseObj(snapshot).set("targetWarehouse", "W3").toString();
		original.setRequestSnapshot(changed).setReceiveRequestSnapshot(changed);
		assertThrows(CheckedException.class, () -> service.lookup("allocation-original-000001", "W1"));
	}

	@Test
	void corruptOrUntypedQuantitiesCannotBeReportedAsConfirmed() {
		String initial = original.getRequestSnapshot();
		for (Object quantity : List.of("2", -1, 0)) {
			String snapshot = JSONUtil.parseObj(initial).set("quantity", quantity).toString();
			original.setRequestSnapshot(snapshot).setReceiveRequestSnapshot(snapshot);
			assertThrows(CheckedException.class, () -> service.lookup("allocation-original-000001", "W1"));
		}
	}
}
