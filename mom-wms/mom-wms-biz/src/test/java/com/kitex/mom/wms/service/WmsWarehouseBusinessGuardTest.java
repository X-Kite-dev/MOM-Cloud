package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.dto.app.WmsInBillDetailAppDTO;
import com.kitex.mom.wms.dto.app.WmsOutBillDetailAppDTO;
import com.kitex.mom.wms.enums.InOutBillStateEnum;
import com.kitex.mom.wms.service.inBill.DefaultWmsInBillService;
import com.kitex.mom.wms.service.inNotice.DefaultWmsInNoticeService;
import com.kitex.mom.wms.service.outBill.DefaultWmsOutBillService;
import com.kitex.mom.wms.service.outNotice.DefaultWmsOutNoticeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WmsWarehouseBusinessGuardTest {
	private RemoteWarehouseService remote;
	private WmsWarehouseBizService configurations;
	private WmsWarehouseBusinessGuard guard;
	private DefaultWmsInBillService inbound;
	private DefaultWmsOutBillService outbound;
	private MdmWarehouseEntity warehouse;

	@BeforeEach
	void setUp() {
		FactoryContextHolder.setFactoryCode("F1");
		TenantContextHolder.setTenantId(1L);
		remote = mock(RemoteWarehouseService.class);
		configurations = mock(WmsWarehouseBizService.class);
		warehouse = new MdmWarehouseEntity().setWarehouseCode("W1").setFactoryCode("F1").setStatus(true);
		warehouse.setTenantId(1L);
		when(remote.getByCode("W1")).thenReturn(R.ok(warehouse));
		when(configurations.list(
				org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<WmsWarehouseBizEntity>>any()))
				.thenReturn(List.of());
		guard = new WmsWarehouseBusinessGuard(remote, configurations);
		inbound = new DefaultWmsInBillService();
		outbound = new DefaultWmsOutBillService();
		ReflectionTestUtils.setField(inbound, "warehouseBusinessGuard", guard);
		ReflectionTestUtils.setField(outbound, "warehouseBusinessGuard", guard);
	}

	@AfterEach
	void clearFactory() {
		FactoryContextHolder.clear();
		TenantContextHolder.clear();
	}

	@Test
	void inboundMustNotAcceptMissingWarehouse() {
		assertThrows(CheckedException.class, () -> inbound.checkWarehouse("", "purchase"));
	}

	@Test
	void skippedOrganizationScopeCannotAuthorizeWarehouseBusiness() {
		TenantContextHolder.setTenantSkip();
		assertThrows(CheckedException.class, () -> inbound.checkWarehouse("W1", "purchase"));
		TenantContextHolder.clear();
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactorySkip();
		assertThrows(CheckedException.class, () -> outbound.checkWarehouse("W1", "sales"));
		verifyZeroInteractions(configurations);
	}

	@Test
	void outboundMustNotAcceptMissingWarehouse() {
		assertThrows(CheckedException.class, () -> outbound.checkWarehouse("", "sales"));
	}

	@Test
	void rejectsNonexistentWarehouseEvenWhenNoBusinessConfigurationExists() {
		when(remote.getByCode("W1")).thenReturn(R.ok(null));

		assertThrows(CheckedException.class, () -> inbound.checkWarehouse("W1", "purchase"));
	}

	@Test
	void rejectsDisabledWarehouse() {
		warehouse.setStatus(false);

		assertThrows(CheckedException.class, () -> outbound.checkWarehouse("W1", "sales"));
	}

	@Test
	void rejectsWarehouseFromAnotherFactory() {
		warehouse.setFactoryCode("F2");

		assertThrows(CheckedException.class, () -> inbound.checkWarehouse("W1", "purchase"));
	}

	@Test
	void rejectsWarehouseWithoutFactoryOwnership() {
		warehouse.setFactoryCode("");

		assertThrows(CheckedException.class, () -> inbound.checkWarehouse("W1", "purchase"));
	}

	@Test
	void remoteFailureDoesNotFallBackToLegacyAllowance() {
		when(remote.getByCode("W1")).thenReturn(R.failed("unavailable"));

		assertThrows(CheckedException.class, () -> outbound.checkWarehouse("W1", "sales"));
	}

	@Test
	void configurationFailureDoesNotFallBackToLegacyAllowance() {
		when(configurations.list(
				org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<WmsWarehouseBizEntity>>any()))
				.thenThrow(new IllegalStateException("database unavailable"));

		assertThrows(IllegalStateException.class, () -> inbound.checkWarehouse("W1", "purchase"));
	}

	@Test
	void missingFactoryDoesNotTurnIntoUnscopedWarehouseWrite() {
		FactoryContextHolder.clear();

		assertThrows(CheckedException.class, () -> inbound.checkWarehouse("W1", "purchase"));
	}

	@Test
	void missingTenantDoesNotTurnIntoUnscopedWarehouseWrite() {
		TenantContextHolder.clear();

		assertThrows(CheckedException.class, () -> outbound.checkWarehouse("W1", "sales"));
	}

	@Test
	void rejectsWarehouseFromAnotherTenant() {
		warehouse.setTenantId(2L);

		assertThrows(CheckedException.class, () -> inbound.checkWarehouse("W1", "purchase"));
	}

	@Test
	void preservesUnconfiguredLegacyWarehouse() {
		assertTrue(inbound.checkWarehouse("W1", "purchase"));
		assertTrue(outbound.checkWarehouse("W1", "sales"));
	}

	@Test
	void legacyWarehouseRejectsUnknownBusinessType() {
		var evaluation = guard.evaluate("W1", "unknown", true);

		assertFalse(evaluation.allowed());
		assertEquals("LEGACY_UNCONFIGURED", evaluation.configurationSource());
		assertEquals("业务类型没有对应的标准仓库作业", evaluation.reason());
		assertThrows(CheckedException.class, () -> guard.check("W1", "unknown", true));
	}

	@Test
	void previewDistinguishesLegacyAllowanceFromConfiguredAllowance() {
		var legacy = guard.evaluate("W1", "purchase", true);
		assertTrue(legacy.allowed());
		assertEquals("LEGACY_UNCONFIGURED", legacy.configurationSource());
		assertEquals("/wms/inBiz/purchase", legacy.requiredOperation());

		allow("/wms/inBiz/purchase");
		var configured = guard.evaluate("W1", "purchase", true);
		assertTrue(configured.allowed());
		assertEquals("WAREHOUSE_CONFIGURATION", configured.configurationSource());
	}

	@Test
	void businessMatrixUsesTheWriteGateAndReadsWarehouseConfigurationOnce() {
		allow("/wms/inBiz/purchase");

		var matrix = guard.previewMatrix("W1");
		var purchase = matrix.stream().filter(row -> row.inbound() && "purchase".equals(row.billType()))
				.findFirst().orElseThrow();
		var product = matrix.stream().filter(row -> row.inbound() && "product".equals(row.billType()))
				.findFirst().orElseThrow();
		var sales = matrix.stream().filter(row -> !row.inbound() && "sales".equals(row.billType()))
				.findFirst().orElseThrow();

		assertTrue(purchase.allowed());
		assertEquals("WAREHOUSE_CONFIGURATION", purchase.configurationSource());
		assertFalse(product.allowed());
		assertFalse(sales.allowed());
		assertEquals(1, matrix.stream().filter(row -> row.inbound() && "purchase".equals(row.billType())).count());
		verify(remote).getByCode("W1");
		verify(configurations).list(
				org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<WmsWarehouseBizEntity>>any());
	}

	@Test
	void businessMatrixExplainsStandardOwnershipWithoutChangingWarehouseAccess() {
		allow("/wms/inBiz/purchase");
		var matrix = guard.previewMatrix("W1");
		var purchase = matrix.stream().filter(row -> row.inbound() && "purchase".equals(row.billType()))
				.findFirst().orElseThrow();
		var otherIn = matrix.stream().filter(row -> row.inbound() && "other_in".equals(row.billType()))
				.findFirst().orElseThrow();
		var otherOut = matrix.stream().filter(row -> !row.inbound() && "other_out".equals(row.billType()))
				.findFirst().orElseThrow();

		assertTrue(purchase.allowed());
		assertEquals(List.of("OWNED"), purchase.allowedOwnerTypes());
		assertEquals("NONE", purchase.externalIdentityRequirement());
		assertFalse(otherIn.allowed());
		assertEquals(List.of("OWNED", "CUSTOMER_SUPPLIED", "CONSIGNED"), otherIn.allowedOwnerTypes());
		assertEquals("OWNER_AND_SOURCE_NO", otherIn.externalIdentityRequirement());
		assertEquals("OWNER_AND_STOCK_ID", otherOut.externalIdentityRequirement());
	}

	@Test
	void businessMatrixShowsRestrictedOtherOutboundSubtypes() {
		when(configurations.list(
				org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<WmsWarehouseBizEntity>>any()))
				.thenReturn(List.of(config("/wms/outBiz/other"), config("wms:other_out:types"),
						config("wms:other_out:other_retail")));

		var matrix = guard.previewMatrix("W1");
		assertTrue(matrix.stream().anyMatch(row -> "other_retail".equals(row.billType()) && row.allowed()));
		assertTrue(matrix.stream().anyMatch(row -> "other_pms".equals(row.billType()) && !row.allowed()));
	}

	@Test
	void businessMatrixIncludesBothInventoryInboundCodes() {
		allow("/wms/commonBiz/insideInventoryPlan/index");

		var matrix = guard.previewMatrix("W1");
		assertTrue(matrix.stream().anyMatch(row -> row.inbound()
				&& "other_inventory".equals(row.billType()) && row.allowed()));
		assertTrue(matrix.stream().anyMatch(row -> row.inbound()
				&& "inventory".equals(row.billType()) && row.allowed()));
	}

	@Test
	void previewReportsDeniedAndEmptyWarehouseConfiguration() {
		allow("/wms/inBiz/product");
		var denied = guard.evaluate("W1", "purchase", true);
		assertFalse(denied.allowed());
		assertEquals("/wms/inBiz/purchase", denied.requiredOperation());
		assertThrows(CheckedException.class, () -> guard.check("W1", "purchase", true));

		allow("NO_BUSINESS");
		assertFalse(guard.evaluate("W1", "purchase", true).allowed());
		assertFalse(guard.evaluate("W1", "unknown", true).allowed());
	}

	@Test
	void previewUsesOtherOutboundSubtypeSelection() {
		when(configurations.list(
				org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<WmsWarehouseBizEntity>>any()))
				.thenReturn(List.of(config("/wms/outBiz/other"), config("wms:other_out:types"),
						config("wms:other_out:other_retail")));

		assertTrue(guard.evaluate("W1", "other_retail", false).allowed());
		assertFalse(guard.evaluate("W1", "other_pms", false).allowed());
		assertFalse(guard.evaluate("W1", "other_retail", true).allowed());
	}

	@Test
	void previewKeepsWarehouseScopeProtection() {
		warehouse.setFactoryCode("F2");
		assertThrows(CheckedException.class, () -> guard.evaluate("W1", "purchase", true));
	}

	@Test
	void configuredWarehouseAllowsOnlySelectedBusinessPaths() {
		allow("/wms/inBiz/purchase");

		assertTrue(inbound.checkWarehouse("W1", "purchase"));
		assertThrows(CheckedException.class, () -> inbound.checkWarehouse("W1", "product"));
		assertThrows(CheckedException.class, () -> outbound.checkWarehouse("W1", "sales"));
	}

	@Test
	void genericDocumentMenuDoesNotGrantAllBusinessTypes() {
		allow("/wms/commonBiz/inBill/index");

		assertThrows(CheckedException.class, () -> inbound.checkWarehouse("W1", "purchase"));
	}

	@Test
	void inventoryMenuAllowsBothProfitAndLossPosting() {
		allow("/wms/commonBiz/insideInventoryPlan/index");

		assertTrue(inbound.checkWarehouse("W1", "other_inventory"));
		assertTrue(inbound.checkWarehouse("W1", "inventory"));
		assertTrue(outbound.checkWarehouse("W1", "other_inventory"));
		assertThrows(CheckedException.class, () -> outbound.checkWarehouse("W1", "sales"));
	}

	@Test
	void otherOutboundSubtypesUseExistingOtherOutboundConfiguration() {
		allow("/wms/outBiz/other");

		for (String type : List.of("other_out", "other_retail", "other_outsource", "other_pms")) {
			assertTrue(outbound.checkWarehouse("W1", type));
		}
		assertThrows(CheckedException.class, () -> outbound.checkWarehouse("W1", "produce"));
		assertThrows(CheckedException.class, () -> guard.check("W1", "other_retail", true));
	}

	@Test
	void scopedOtherOutboundConfigurationRestrictsEachSubtype() {
		when(configurations.list(
				org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<WmsWarehouseBizEntity>>any()))
				.thenReturn(List.of(config("/wms/outBiz/other"), config("wms:other_out:types"),
						config("wms:other_out:other_retail")));

		assertTrue(outbound.checkWarehouse("W1", "other_retail"));
		assertThrows(CheckedException.class, () -> outbound.checkWarehouse("W1", "other_outsource"));
		assertThrows(CheckedException.class, () -> outbound.checkWarehouse("W1", "other_pms"));
	}

	@Test
	void transferReceiptAndShipmentUseDifferentConfiguredOperations() {
		allow("/wms/commonBiz/allocationBill/receive/index");

		assertTrue(inbound.checkWarehouse("W1", "allocation"));
		assertThrows(CheckedException.class, () -> outbound.checkWarehouse("W1", "allocation"));

		allow("/wms/commonBiz/allocationBill/bill/index");

		assertTrue(outbound.checkWarehouse("W1", "allocation"));
		assertThrows(CheckedException.class, () -> inbound.checkWarehouse("W1", "allocation"));
	}

	@Test
	void moveSiteUsesItsOwnWarehouseOperation() {
		allow("/wms/commonBiz/materialMoveSite/index");

		assertTrue(guard.check("W1", "move_site", false));
		assertFalse(guard.evaluate("W1", "move_site", true).allowed());
		assertThrows(CheckedException.class, () -> guard.check("W1", "allocation", false));
	}

	@Test
	void rereadsConfigurationForEachOperation() {
		when(configurations.list(
				org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<WmsWarehouseBizEntity>>any()))
				.thenReturn(
						List.of(config("/wms/inBiz/purchase")), List.of(config("/wms/inBiz/product")));

		assertTrue(inbound.checkWarehouse("W1", "purchase"));
		assertThrows(CheckedException.class, () -> inbound.checkWarehouse("W1", "purchase"));
	}

	@Test
	void accountingBoundaryRejectsDeniedBusinessBeforeWritingInventory() {
		allow("/wms/inBiz/product");

		assertThrows(CheckedException.class, () -> inbound.afterInStock(inBill()));
		assertThrows(CheckedException.class, () -> outbound.afterOutStock(outBill()));
	}

	@Test
	void noticeCreationChecksTheCurrentWarehouseBusinessConfiguration() {
		allow("/wms/inBiz/product");
		DefaultWmsInNoticeService inNotice = new DefaultWmsInNoticeService();
		DefaultWmsOutNoticeService outNotice = new DefaultWmsOutNoticeService();
		ReflectionTestUtils.setField(inNotice, "warehouseBusinessGuard", guard);
		ReflectionTestUtils.setField(outNotice, "warehouseBusinessGuard", guard);

		assertThrows(CheckedException.class, () -> inNotice.checkWarehouse("W1", "purchase"));
		assertThrows(CheckedException.class, () -> outNotice.checkWarehouse("W1", "sales"));
		assertTrue(inNotice.checkWarehouse("W1", "product"));
	}

	@Test
	void approvalRevalidatesCurrentConfiguration() {
		allow("/wms/inBiz/product");
		WmsInBillMapper inMapper = mock(WmsInBillMapper.class);
		WmsInBillEntity bill = inBill();
		bill.setState(InOutBillStateEnum.PENDING_REVIEW.getCode());
		when(inMapper.selectOne(any())).thenReturn(bill);
		ReflectionTestUtils.setField(inbound, "wmsInBillMapper", inMapper);

		assertThrows(CheckedException.class, () -> inbound.passBill(1L));

		WmsOutBillMapper outMapper = mock(WmsOutBillMapper.class);
		WmsOutBillEntity out = outBill();
		out.setState(InOutBillStateEnum.PENDING_REVIEW.getCode());
		when(outMapper.selectOne(any())).thenReturn(out);
		ReflectionTestUtils.setField(outbound, "wmsOutBillMapper", outMapper);

		assertThrows(CheckedException.class, () -> outbound.passBill(1L));
	}

	@Test
	void pdaExistingBillRevalidatesBeforeChangingQuantities() {
		allow("/wms/inBiz/product");
		WmsInNoticeMapper inMapper = mock(WmsInNoticeMapper.class);
		WmsInNoticeEntity inNotice = new WmsInNoticeEntity();
		inNotice.setWarehouseCode("W1");
		inNotice.setBillType("purchase");
		when(inMapper.selectById(1L)).thenReturn(inNotice);
		ReflectionTestUtils.setField(inbound, "wmsInNoticeMapper", inMapper);
		WmsInBillDetailAppDTO in = new WmsInBillDetailAppDTO();
		in.setNoticeId(1L);

		assertThrows(CheckedException.class, () -> inbound.setInBillNum(in));

		WmsOutNoticeMapper outMapper = mock(WmsOutNoticeMapper.class);
		WmsOutNoticeEntity outNotice = new WmsOutNoticeEntity();
		outNotice.setWarehouseCode("W1");
		outNotice.setBillType("sales");
		when(outMapper.selectById(1L)).thenReturn(outNotice);
		ReflectionTestUtils.setField(outbound, "wmsOutNoticeMapper", outMapper);
		WmsOutBillDetailAppDTO out = new WmsOutBillDetailAppDTO();
		out.setNoticeId(1L);

		assertThrows(CheckedException.class, () -> outbound.setOutBillNum(out));
	}

	@Test
	void falseValidationResultCannotBeIgnoredBySaveCallers() {
		DefaultWmsInBillService denyIn = new DefaultWmsInBillService() {
			@Override
			public void checkData(WmsInBillEntity ignored) {
			}

			@Override
			public Boolean checkWarehouse(String warehouse, String type) {
				return false;
			}
		};

		ReflectionTestUtils.setField(denyIn, "identifierPolicyService", mock(WmsIdentifierPolicyService.class));
		WmsInBillEntity validInbound = inBill();
		validInbound.setDetails(List.of(new WmsInBillDetailEntity()
				.setMatCode("M1").setMatUnit("EA").setMatNum(1D)));
		assertThrows(IllegalArgumentException.class, () -> denyIn.beforeSaveDeep(validInbound));

		DefaultWmsOutBillService denyOut = new DefaultWmsOutBillService() {
			@Override
			public void checkData(WmsOutBillEntity ignored) {
			}

			@Override
			public Boolean checkWarehouse(String warehouse, String type) {
				return false;
			}
		};
		ReflectionTestUtils.setField(denyOut, "materialReservationService", mock(WmsMaterialReservationService.class));
		ReflectionTestUtils.setField(denyOut, "salesStockReservationService",
				mock(WmsSalesStockReservationService.class));

		assertThrows(IllegalArgumentException.class, () -> denyOut.beforeSaveDeep(outBill()));
	}

	private WmsInBillEntity inBill() {
		WmsInBillEntity bill = new WmsInBillEntity();
		bill.setWarehouseCode("W1");
		bill.setBillType("purchase");
		return bill;
	}

	private WmsOutBillEntity outBill() {
		WmsOutBillEntity bill = new WmsOutBillEntity();
		bill.setWarehouseCode("W1");
		bill.setBillType("sales");
		return bill;
	}

	private void allow(String path) {
		when(configurations.list(
				org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<WmsWarehouseBizEntity>>any()))
				.thenReturn(List.of(config(path)));
	}

	private WmsWarehouseBizEntity config(String path) {
		return new WmsWarehouseBizEntity().setWarehouseCode("W1").setFactoryCode("F1").setBizType(path);
	}
}
