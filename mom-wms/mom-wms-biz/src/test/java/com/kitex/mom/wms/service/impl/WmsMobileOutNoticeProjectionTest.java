package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.util.R;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.WmsMaterialReservationService;
import com.kitex.mom.wms.service.WmsSalesStockReservationService;
import com.kitex.mom.wms.service.outNotice.DefaultWmsOutNoticeService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Execute the actual recommendation and APP projection. Only persistence, MDM and reservation boundaries are adapted;
 * this is not a real SQL/HTTP or outbound-write integration test.
 */
class WmsMobileOutNoticeProjectionTest {
	private DefaultWmsOutNoticeService service;
	private boolean frozen;
	private boolean inspectionRequired;
	private String qualityResult = "QUALIFIED";
	private BigDecimal salesReserved = BigDecimal.ZERO;
	private BigDecimal productionReserved = BigDecimal.ZERO;
	private List<WmsOutNoticeDetailEntity> requestedDetails;
	private String stockOwnerType;
	private String stockOwnerCode;
	private String sourceBusinessNo;
	private String billType;
	private Long stockTenantId = 1L;
	private List<WmsLiveStockEntity> candidateStocks;
	private List<WmsLiveStockEntity> reservedCandidateStocks;
	private WmsMaterialReservationService materialReservations;
	private WmsSalesStockReservationService salesReservations;

	@BeforeEach
	void setup() {
		for (Class<?> type : List.of(WmsOutNoticeEntity.class, WmsOutNoticeDetailEntity.class,
				WmsLiveStockEntity.class, WmsOutBillEntity.class)) {
			TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "mobile-out-notice"),
					type);
		}
		service = new DefaultWmsOutNoticeService();
		var notices = mock(WmsOutNoticeMapper.class);
		when(notices.selectById(1L)).thenAnswer(call -> {
			var notice = new WmsOutNoticeEntity();
			notice.setId(1L);
			notice.setTenantId(1L);
			notice.setFactoryCode("F1");
			notice.setWarehouseCode("W1");
			notice.setBillType(billType);
			return notice;
		});
		var details = mock(WmsOutNoticeDetailMapper.class);
		when(details.selectList(any())).thenAnswer(call -> {
			if (requestedDetails != null) {
				return requestedDetails;
			}
			var row = new WmsOutNoticeDetailEntity();
			row.setId(2L);
			row.setNoticeId(1L);
			row.setTenantId(1L);
			row.setFactoryCode("F1");
			row.setWarehouseCode("W1");
			row.setMatCode("M1");
			row.setMatUnit("KG");
			row.setNoticeOutNum(10d);
			row.setOutNum(0d);
			row.setNotOutNum(10d);
			row.setSourceStockId(frozen ? 3L : null);
			return List.of(row);
		});
		var stocks = mock(WmsLiveStockMapper.class);
		when(stocks.selectList(any())).thenAnswer(call -> candidateStocks == null ? List.of(stock()) : candidateStocks);
		when(stocks.selectById(3L)).thenAnswer(call -> stock());
		var bills = mock(WmsOutBillMapper.class);
		when(bills.selectList(any())).thenReturn(List.of());
		var mdm = mock(RemoteMaterialService.class);
		var material = new MdmMaterialEntity();
		material.setFifo(false);
		when(mdm.getDetails("M1")).thenReturn(R.ok(material));
		var reservations = mock(WmsMaterialReservationService.class);
		when(reservations.activeReservedNum(anyLong())).thenAnswer(call ->
				Long.valueOf(3L).equals(call.getArgument(0)) ? productionReserved : BigDecimal.ZERO);
		when(reservations.availableReservedStocks("R1", "M1", "W1")).thenAnswer(call -> {
			if (reservedCandidateStocks != null) {
				return reservedCandidateStocks;
			}
			var reservedStock = stock();
			reservedStock.setMatNum(productionReserved.doubleValue());
			return List.of(reservedStock);
		});
		var sales = mock(WmsSalesStockReservationService.class);
		when(sales.activeReservedNums(anySet())).thenAnswer(call ->
				salesReserved.signum() > 0 ? Map.of(3L, salesReserved) : Map.of());
		when(sales.availableReservedStocks("SALE-30", "M1", "W1")).thenAnswer(call -> {
			var reservedStock = stock();
			reservedStock.setMatNum(salesReserved.doubleValue());
			return List.of(reservedStock);
		});
		materialReservations = reservations;
		salesReservations = sales;
		ReflectionTestUtils.setField(service, "wmsOutNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsOutNoticeDetailMapper", details);
		ReflectionTestUtils.setField(service, "wmsLiveStockMapper", stocks);
		ReflectionTestUtils.setField(service, "wmsOutBillMapper", bills);
		ReflectionTestUtils.setField(service, "remoteMaterialService", mdm);
		ReflectionTestUtils.setField(service, "materialReservationService", reservations);
		ReflectionTestUtils.setField(service, "salesStockReservationService", sales);
	}

	private WmsLiveStockEntity stock() {
		var row = new WmsLiveStockEntity();
		row.setId(3L);
		row.setTenantId(stockTenantId);
		row.setFactoryCode("F1");
		row.setWarehouseCode("W1");
		row.setMatCode("M1");
		row.setMatBatch("B1");
		row.setMatNum(10d);
		row.setMatUnit("KG");
		row.setSupplierCode("SUP1");
		row.setSupplierName("Supplier");
		row.setSiteCode("S1");
		row.setSiteName("Source");
		row.setContainerCode("C1");
		row.setStatus(0);
		row.setQualityStatus(qualityResult == null ? null : "QUALIFIED");
		row.setQualityResult(qualityResult);
		row.setIsQualityTest(inspectionRequired ? "1" : "0");
		row.setStockOwnerType(stockOwnerType);
		row.setStockOwnerCode(stockOwnerCode);
		row.setSourceBusinessNo(sourceBusinessNo);
		return row;
	}

	@Test
	void ordinaryNoticeDoesNotAcquireAFrozenIdentityFromItsRecommendation() {
		var result = service.getLiveStockByNoticeIdApp(1L);

		assertEquals(3L, result.getDetails().get(0).getSourceStockId());

		var original = result.getNoticeDetails().get(0);

		assertNull(original.getSourceStockId(), "A recommendation is not a frozen DB notice identity");
		assertNull(original.getMatBatch());
		assertNull(original.getSiteCode());
		assertNull(original.getContainerCode());
		assertEquals(10d, original.getNotOutNum());
	}

	@Test
	void frozenNoticeKeepsItsExactStockAndReceivesActualSourceAttributes() {
		frozen = true;

		var result = service.getLiveStockByNoticeIdApp(1L);

		var original = result.getNoticeDetails().get(0);

		assertEquals(3L, original.getSourceStockId());
		assertEquals("B1", original.getMatBatch());
		assertEquals("S1", original.getSiteCode());
		assertEquals("C1", original.getContainerCode());
		assertEquals("SUP1", original.getSupplierCode());
		assertEquals(10d, original.getNotOutNum());
	}

	@Test
	void exactOtherIssueCanRecommendCustomerOwnedStockWithItsSource() {
		billType = "other_out";
		stockOwnerType = "CUSTOMER_SUPPLIED";
		stockOwnerCode = "CUSTOMER-1";
		sourceBusinessNo = "ORDER-1";
		var line = noticeLine(2L, 3D, 3L);
		line.setStockOwnerType(stockOwnerType);
		line.setStockOwnerCode(stockOwnerCode);
		requestedDetails = List.of(line);

		var result = service.getLiveStockByNoticeId(1L).getDetails();

		assertEquals(1, result.size());
		assertEquals(3D, result.get(0).getMatNum());
		assertEquals("CUSTOMER_SUPPLIED", result.get(0).getStockOwnerType());
		assertEquals("CUSTOMER-1", result.get(0).getStockOwnerCode());
		assertEquals("ORDER-1", result.get(0).getSourceBusinessNo());
	}

	@Test
	void exactOtherIssueRejectsAnotherOwnerBeforeRecommendation() {
		billType = "other_out";
		stockOwnerType = "CUSTOMER_SUPPLIED";
		stockOwnerCode = "CUSTOMER-1";
		var line = noticeLine(2L, 3D, 3L);
		line.setStockOwnerType(stockOwnerType);
		line.setStockOwnerCode("CUSTOMER-2");
		requestedDetails = List.of(line);

		assertThrows(CheckedException.class, () -> service.getLiveStockByNoticeId(1L));
	}

	@Test
	void ordinaryNoticeDoesNotRecommendStockFromAnotherTenantOrFactory() {
		var otherTenant = stock();
		otherTenant.setId(4L);
		otherTenant.setTenantId(2L);
		var otherFactory = stock();
		otherFactory.setId(5L);
		otherFactory.setFactoryCode("F2");
		var ownedStock = stock();
		candidateStocks = List.of(otherTenant, otherFactory, ownedStock);
		requestedDetails = List.of(noticeLine(2L, 3D, null));

		var recommendation = service.getLiveStockByNoticeId(1L).getDetails();

		assertEquals(1, recommendation.size());
		assertEquals(3L, recommendation.get(0).getSourceStockId());
		assertEquals(3D, recommendation.get(0).getMatNum());
	}

	@Test
	void exactNoticeCannotRecommendStockFromAnotherTenant() {
		stockTenantId = 2L;
		frozen = true;

		assertThrows(CheckedException.class, () -> service.getLiveStockByNoticeId(1L));
	}

	@Test
	void stockWithoutRequiredInspectionOrConclusionCanUseTheExistingLegacyPolicy() {
		qualityResult = null;

		var result = service.getLiveStockByNoticeIdApp(1L);

		assertEquals(3L, result.getDetails().get(0).getSourceStockId());
		assertNull(result.getNoticeDetails().get(0).getSourceStockId());
	}

	@Test
	void requiredInspectionWithoutConclusionCannotBecomeAnAvailableRecommendation() {
		qualityResult = null;
		inspectionRequired = true;

		assertThrows(CheckedException.class, () -> service.getLiveStockByNoticeIdApp(1L));
	}

	@Test
	void ordinaryRecommendationExcludesSalesReservedQuantity() {
		salesReserved = new BigDecimal("5");
		requestedDetails = List.of(noticeLine(2L, 6D, null));

		assertThrows(CheckedException.class, () -> service.getLiveStockByNoticeId(1L));
	}

	@Test
	void multipleLinesCannotRecommendTheSamePhysicalQuantityTwice() {
		requestedDetails = List.of(noticeLine(2L, 6D, null), noticeLine(4L, 6D, null));

		assertThrows(CheckedException.class, () -> service.getLiveStockByNoticeId(1L));
	}

	@Test
	void fixedAndOrdinaryLinesShareOneRecommendationBudget() {
		requestedDetails = List.of(noticeLine(2L, 6D, 3L), noticeLine(4L, 6D, null));

		assertThrows(CheckedException.class, () -> service.getLiveStockByNoticeId(1L));
	}

	@Test
	void reservedAndFreeQuantityOnOneStockCanBothBeRecommended() {
		productionReserved = new BigDecimal("6");
		var reserved = noticeLine(2L, 6D, null);
		reserved.setReservationNo("R1");
		requestedDetails = List.of(reserved, noticeLine(4L, 4D, null));

		var recommendation = service.getLiveStockByNoticeId(1L).getDetails();

		assertEquals(2, recommendation.size());
		assertEquals(6D, recommendation.get(0).getMatNum());
		assertEquals(4D, recommendation.get(1).getMatNum());
	}

	@Test
	void exactProductionReservationUsesItsReservedShare() {
		productionReserved = new BigDecimal("6");
		var reserved = noticeLine(2L, 6D, 3L);
		reserved.setReservationNo("R1");
		requestedDetails = List.of(reserved, noticeLine(4L, 4D, null));

		var recommendation = service.getLiveStockByNoticeId(1L).getDetails();

		assertEquals(2, recommendation.size());
		assertEquals(6D, recommendation.get(0).getMatNum());
		assertEquals("R1", recommendation.get(0).getReservationNo());
		assertEquals(3L, recommendation.get(0).getSourceStockId());
		assertEquals(4D, recommendation.get(1).getMatNum());
	}

	@Test
	void salesReservationUsesItsOwnReservedShareForRecommendation() {
		salesReserved = new BigDecimal("6");
		var reserved = noticeLine(2L, 6D, null);
		reserved.setReservationNo("SALE-30");
		requestedDetails = List.of(reserved, noticeLine(4L, 4D, null));

		var recommendation = service.getLiveStockByNoticeId(1L).getDetails();

		assertEquals(2, recommendation.size());
		assertEquals(6D, recommendation.get(0).getMatNum());
		assertEquals("SALE-30", recommendation.get(0).getReservationNo());
		assertEquals(3L, recommendation.get(0).getSourceStockId());
		assertEquals(4D, recommendation.get(1).getMatNum());
		verify(salesReservations).availableReservedStocks("SALE-30", "M1", "W1");
		verify(materialReservations, never()).availableReservedStocks("SALE-30", "M1", "W1");
	}

	@Test
	void exactSalesReservationUsesItsReservedShare() {
		salesReserved = new BigDecimal("6");
		var reserved = noticeLine(2L, 6D, 3L);
		reserved.setReservationNo("SALE-30");
		requestedDetails = List.of(reserved, noticeLine(4L, 4D, null));

		var recommendation = service.getLiveStockByNoticeId(1L).getDetails();

		assertEquals(2, recommendation.size());
		assertEquals(6D, recommendation.get(0).getMatNum());
		assertEquals("SALE-30", recommendation.get(0).getReservationNo());
		assertEquals(3L, recommendation.get(0).getSourceStockId());
		assertEquals(4D, recommendation.get(1).getMatNum());
	}

	@Test
	void repeatedSalesReservationLinesCannotRecommendTheSameShareTwice() {
		salesReserved = new BigDecimal("6");
		var first = noticeLine(2L, 4D, null);
		var second = noticeLine(4L, 4D, null);
		first.setReservationNo("SALE-30");
		second.setReservationNo("SALE-30");
		requestedDetails = List.of(first, second);

		assertThrows(CheckedException.class, () -> service.getLiveStockByNoticeId(1L));
	}

	@Test
	void reservedNoticeDoesNotRecommendStockFromAnotherTenant() {
		var otherTenant = stock();
		otherTenant.setId(4L);
		otherTenant.setTenantId(2L);
		otherTenant.setMatNum(3D);
		var ownStock = stock();
		ownStock.setMatNum(3D);
		reservedCandidateStocks = List.of(otherTenant, ownStock);
		var line = noticeLine(2L, 3D, null);
		line.setReservationNo("R1");
		requestedDetails = List.of(line);

		var recommendation = service.getLiveStockByNoticeId(1L).getDetails();

		assertEquals(1, recommendation.size());
		assertEquals(3L, recommendation.get(0).getSourceStockId());
		assertEquals(3D, recommendation.get(0).getMatNum());
	}

	@Test
	void repeatedReservationLinesCannotUseTheirReservedShareTwice() {
		productionReserved = new BigDecimal("6");
		var first = noticeLine(2L, 4D, null);
		var second = noticeLine(4L, 4D, null);
		first.setReservationNo("R1");
		second.setReservationNo("R1");
		requestedDetails = List.of(first, second);

		assertThrows(CheckedException.class, () -> service.getLiveStockByNoticeId(1L));
	}

	private static WmsOutNoticeDetailEntity noticeLine(Long id, Double quantity, Long sourceStockId) {
		var row = new WmsOutNoticeDetailEntity();
		row.setId(id);
		row.setNoticeId(1L);
		row.setTenantId(1L);
		row.setFactoryCode("F1");
		row.setWarehouseCode("W1");
		row.setMatCode("M1");
		row.setMatUnit("KG");
		row.setNoticeOutNum(quantity);
		row.setOutNum(0D);
		row.setNotOutNum(quantity);
		row.setSourceStockId(sourceStockId);
		return row;
	}
}
