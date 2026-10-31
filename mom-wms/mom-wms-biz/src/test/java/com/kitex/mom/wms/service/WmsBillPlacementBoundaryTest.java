package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.entity.WmsOutBillDetailEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.dto.app.WmsInBillDetailAppDTO;
import com.kitex.mom.wms.dto.app.WmsOutBillDetailAppDTO;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import com.kitex.mom.wms.enums.InOutBillStateEnum;
import com.kitex.mom.wms.enums.InOutNoticeStateEnum;
import com.kitex.mom.wms.mapper.WmsInBillMapper;
import com.kitex.mom.wms.mapper.WmsInBillDetailMapper;
import com.kitex.mom.wms.mapper.WmsOutBillMapper;
import com.kitex.mom.wms.mapper.WmsOutBillDetailMapper;
import com.kitex.mom.wms.mapper.WmsOutNoticeMapper;
import com.kitex.mom.wms.mapper.WmsOutNoticeDetailMapper;
import com.kitex.mom.wms.service.inBill.DefaultWmsInBillService;
import com.kitex.mom.wms.service.outBill.DefaultWmsOutBillService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WmsBillPlacementBoundaryTest {

	@BeforeAll
	static void registerTables() {
		for (Class<?> entity : List.of(WmsInBillEntity.class, WmsInBillDetailEntity.class,
				WmsOutBillEntity.class, WmsOutBillDetailEntity.class)) {
			TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), entity.getSimpleName()), entity);
		}
	}

	@Test
	void inboundSubmitRechecksPlacementBeforeChangingReviewState() {
		var handler = new DefaultWmsInBillService();
		var mapper = mock(WmsInBillMapper.class);
		var details = mock(WmsInBillDetailMapper.class);
		var bill = inbound();
		bill.setState(InOutBillStateEnum.PENDING_COMMIT.getCode());
		when(mapper.selectById(7L)).thenReturn(bill);
		when(details.selectList(any())).thenReturn(bill.getDetails());
		when(mapper.update(any())).thenReturn(1);
		ReflectionTestUtils.setField(handler, "wmsInBillMapper", mapper);
		ReflectionTestUtils.setField(handler, "wmsInBillDetailMapper", details);
		ReflectionTestUtils.setField(handler, "warehouseBusinessGuard", blocked());

		var error = assertThrows(CheckedException.class, () -> handler.commitBill(7L));

		assertEquals("placement blocked", error.getMessage());
		verify(mapper, never()).update(any());
	}

	@Test
	void outboundSubmitRechecksPlacementBeforeChangingReviewState() {
		var handler = new DefaultWmsOutBillService();
		var mapper = mock(WmsOutBillMapper.class);
		var details = mock(WmsOutBillDetailMapper.class);
		var bill = outbound();
		bill.setState(InOutBillStateEnum.PENDING_COMMIT.getCode());
		when(mapper.selectById(7L)).thenReturn(bill);
		when(details.selectList(any())).thenReturn(bill.getDetails());
		when(mapper.update(any())).thenReturn(1);
		ReflectionTestUtils.setField(handler, "wmsOutBillMapper", mapper);
		ReflectionTestUtils.setField(handler, "wmsOutBillDetailMapper", details);
		ReflectionTestUtils.setField(handler, "warehouseBusinessGuard", blocked());

		var error = assertThrows(CheckedException.class, () -> handler.commitBill(7L));

		assertEquals("placement blocked", error.getMessage());
		verify(mapper, never()).update(any());
	}

	@Test
	void inboundSaveRejectsPlacementBeforeAllocatingAnyIdentifier() {
		var handler = new DefaultWmsInBillService();
		var identifiers = mock(WmsIdentifierPolicyService.class);
		ReflectionTestUtils.setField(handler, "warehouseBusinessGuard", blocked());
		ReflectionTestUtils.setField(handler, "identifierPolicyService", identifiers);
		var bill = inbound();

		assertThrows(CheckedException.class, () -> handler.beforeSaveDeep(bill));
		verify(identifiers, never()).fillMissingIdentifiers(anyString(), any());
	}

	@Test
	void outboundSaveCannotBypassPlacementThroughDefaultOtherBusiness() {
		var handler = new DefaultWmsOutBillService();
		var sequence = mock(RemoteSeqConfigService.class);
		when(sequence.create(anyString())).thenReturn(R.ok("OUT-1"));
		ReflectionTestUtils.setField(handler, "warehouseBusinessGuard", blocked());
		ReflectionTestUtils.setField(handler, "remoteSeqConfigService", sequence);
		ReflectionTestUtils.setField(handler, "materialReservationService", mock(WmsMaterialReservationService.class));
		ReflectionTestUtils.setField(handler, "salesStockReservationService", mock(WmsSalesStockReservationService.class));
		var bill = outbound();

		assertThrows(CheckedException.class, () -> handler.beforeSaveDeep(bill));
		verify(sequence, never()).create(anyString());
	}

	@Test
	void inboundPostingChecksCurrentPlacementBeforeReadingStockOrSource() {
		var handler = new DefaultWmsInBillService();
		ReflectionTestUtils.setField(handler, "warehouseBusinessGuard", blocked());

		var error = assertThrows(CheckedException.class, () -> handler.afterInStock(inbound()));
		assertEquals("placement blocked", error.getMessage());
	}

	@Test
	void outboundPostingChecksCurrentPlacementBeforeStockDeduction() {
		var handler = new DefaultWmsOutBillService();
		ReflectionTestUtils.setField(handler, "warehouseBusinessGuard", blocked());
		var stocks = mock(WmsLiveStockService.class);
		ReflectionTestUtils.setField(handler, "wmsLiveStockService", stocks);

		var error = assertThrows(CheckedException.class, () -> handler.afterOutStock(outbound()));
		assertEquals("placement blocked", error.getMessage());
		verify(stocks, never()).reduceStockWhenOut(any());
	}

	@Test
	void mobileInboundUsesTheSamePlacementPolicyAsPc() {
		var handler = new DefaultWmsInBillService();
		ReflectionTestUtils.setField(handler, "warehouseBusinessGuard", blocked());
		var command = new WmsInBillDetailAppDTO();
		command.setSiteCode("S1");

		assertThrows(CheckedException.class, () -> handler.appInCheck("W1", command));
	}

	@Test
	void mobileOutboundChecksPlacementBeforeReadingOrOccupyingNoticeDetails() {
		var handler = new DefaultWmsOutBillService();
		var notices = mock(WmsOutNoticeMapper.class);
		var details = mock(WmsOutNoticeDetailMapper.class);
		var notice = new WmsOutNoticeEntity();
		notice.setId(7L);
		notice.setTenantId(1L);
		notice.setFactoryCode("F1");
		notice.setWarehouseCode("W1");
		notice.setBillType("sales");
		notice.setState(InOutNoticeStateEnum.READY.getCode());
		notice.setAllNotOutNum(2D);
		notice.setAllOutNum(0D);
		when(notices.selectById(7L)).thenReturn(notice);
		ReflectionTestUtils.setField(handler, "wmsOutNoticeMapper", notices);
		ReflectionTestUtils.setField(handler, "wmsOutNoticeDetailMapper", details);
		ReflectionTestUtils.setField(handler, "warehouseBusinessGuard", blocked());
		var command = new WmsOutBillDetailAppDTO().setNoticeId(7L).setMatCode("M1")
				.setMatBatch("B1").setMatNum(1D).setSiteCode("S1");
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");

		try {
			var error = assertThrows(CheckedException.class, () -> handler.setOutBillNum(command));
			assertEquals("placement blocked", error.getMessage());
			verify(details, never()).selectList(any());
		} finally {
			TenantContextHolder.clear();
			FactoryContextHolder.clear();
		}
	}

	private WmsWarehouseBusinessGuard blocked() {
		var guard = mock(WmsWarehouseBusinessGuard.class);
		when(guard.check(anyString(), anyString(), anyBoolean())).thenReturn(true);
		doThrow(new CheckedException("placement blocked")).when(guard).checkPlacements(eq("W1"), anyList());
		return guard;
	}

	private WmsInBillEntity inbound() {
		var bill = new WmsInBillEntity();
		bill.setWarehouseCode("W1");
		bill.setBillType("other_in");
		bill.setDetails(List.of(new WmsInBillDetailEntity().setMatCode("M1").setMatUnit("EA").setMatNum(1D).setSiteCode("S1")));
		return bill;
	}

	private WmsOutBillEntity outbound() {
		var bill = new WmsOutBillEntity();
		bill.setWarehouseCode("W1");
		bill.setBillType("other_out");
		bill.setDetails(List.of(new WmsOutBillDetailEntity().setMatCode("M1").setMatUnit("EA").setMatNum(1D).setSiteCode("S1")));
		return bill;
	}
}
