package com.kitex.mom.wms.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.service.WmsMaterialReservationService;
import com.kitex.mom.wms.service.WmsSalesStockReservationService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WmsOrganizationStockBoundaryTest {
	WmsLiveStockServiceImpl service = mock(WmsLiveStockServiceImpl.class, CALLS_REAL_METHODS);
	WmsLiveStockMapper stocks = mock(WmsLiveStockMapper.class);

	@BeforeEach
	void setup() {
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F2");
		ReflectionTestUtils.setField(service, "baseMapper", stocks);
		when(stocks.insert(any(WmsLiveStockEntity.class))).thenAnswer(call -> {
			WmsLiveStockEntity row = call.getArgument(0);
			row.setId(50L);
			row.setTenantId(1L);
			return 1;
		});
	}

	@AfterEach
	void clear() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	WmsInoutFlowEntity flow(String factory) {
		var row = new WmsInoutFlowEntity();
		row.setTenantId(1L);
		row.setFactoryCode(factory);
		row.setMatNum(2d);
		row.setMatCode("M1");
		row.setMatUnit("KG");
		return row;
	}

	@Test
	void actualReceivingFactoryGetsAnIndependentPhysicalIdentifier() {
		assertEquals(50L, service.transferInWithEvidence(flow("F2"), "WB", "目标仓", "SB", "库位", null));
	}

	@Test
	void foreignFactoryOrScopeBypassCannotWriteAnOrganizationReceipt() {
		assertThrows(CheckedException.class,
				() -> service.transferInWithEvidence(flow("F1"), "WB", "目标仓", "SB", "库位", null));
		FactoryContextHolder.setFactorySkip();
		assertThrows(CheckedException.class,
				() -> service.transferInWithEvidence(flow("F2"), "WB", "目标仓", "SB", "库位", null));
		verifyZeroInteractions(stocks);
	}

	@Test
	void unsuccessfulPhysicalDeductionCannotBeReportedAsShippingProof() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "org-deduction"),
				WmsLiveStockEntity.class);
		var row = new WmsLiveStockEntity().setMatCode("M1").setMatNum(4d).setWarehouseCode("WB").setFactoryCode(
				"F2").setStatus(0).setStockOwnerType("OWNED");
		row.setId(50L);
		when(stocks.selectOne(any(), eq(false))).thenReturn(row);
		var production = mock(WmsMaterialReservationService.class);
		when(production.activeReservedNum(50L)).thenReturn(BigDecimal.ZERO);
		var sales = mock(WmsSalesStockReservationService.class);
		when(sales.activeReservedNums(any())).thenReturn(Map.of());
		ReflectionTestUtils.setField(service, "wmsMaterialReservationService", production);
		ReflectionTestUtils.setField(service, "wmsSalesStockReservationService", sales);
		assertThrows(CheckedException.class, () -> service.transferOut(50L, 2d, "WB"));
		assertThrows(CheckedException.class, () -> service.transferOut(50L, 4d, "WB"));
	}

}
