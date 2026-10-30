package com.kitex.mom.wms.service.outNotice;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import com.kitex.mom.wms.mapper.WmsOutBillMapper;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.mapper.WmsOutNoticeDetailMapper;
import com.kitex.mom.wms.mapper.WmsOutNoticeMapper;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsOutNoticeWriteBoundaryTest {

	@AfterEach
	void clearScope() {
		FactoryContextHolder.clear();
		TenantContextHolder.clear();
	}

	@BeforeAll
	static void registerTable() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "out-notice-write"),
				WmsOutBillEntity.class);
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "out-notice-header"),
				WmsOutNoticeEntity.class);
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "out-notice-detail"),
				WmsOutNoticeDetailEntity.class);
	}

	@Test
	void creationRejectsUnconfirmedDetailInsert() {
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		WmsOutNoticeDetailMapper details = mock(WmsOutNoticeDetailMapper.class);
		when(notices.insert(any(WmsOutNoticeEntity.class))).thenReturn(1);
		WmsOutNoticeDetailEntity line = new WmsOutNoticeDetailEntity();
		line.setMatCode("M1");
		line.setMatUnit("EA");
		line.setNoticeOutNum(1D);
		WmsOutNoticeEntity notice = new WmsOutNoticeEntity();
		notice.setWarehouseCode("W1");
		notice.setBillType("sales");
		notice.setDetailList(List.of(line));
		DefaultWmsOutNoticeService service = new DefaultWmsOutNoticeService();
		ReflectionTestUtils.setField(service, "wmsOutNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsOutNoticeDetailMapper", details);
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", mock(WmsWarehouseBusinessGuard.class));

		assertThrows(CheckedException.class, () -> service.saveDeep(notice));
	}

	@Test
	void fractionalNoticeLinesHaveExactTotal() {
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		WmsOutNoticeDetailMapper details = mock(WmsOutNoticeDetailMapper.class);
		when(notices.insert(any(WmsOutNoticeEntity.class))).thenReturn(1);
		when(details.insert(any(WmsOutNoticeDetailEntity.class))).thenReturn(1);
		WmsOutNoticeDetailEntity first = new WmsOutNoticeDetailEntity();
		first.setMatCode("M1");
		first.setMatUnit("EA");
		first.setNoticeOutNum(0.1D);
		WmsOutNoticeDetailEntity second = new WmsOutNoticeDetailEntity();
		second.setMatCode("M1");
		second.setMatUnit("EA");
		second.setNoticeOutNum(0.2D);
		WmsOutNoticeEntity notice = new WmsOutNoticeEntity();
		notice.setWarehouseCode("W1");
		notice.setBillType("sales");
		notice.setDetailList(List.of(first, second));
		DefaultWmsOutNoticeService service = new DefaultWmsOutNoticeService();
		ReflectionTestUtils.setField(service, "wmsOutNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsOutNoticeDetailMapper", details);
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", mock(WmsWarehouseBusinessGuard.class));

		service.saveDeep(notice);

		assertEquals(0.3D, notice.getNoticeOutNum());
	}

	@Test
	void externalOwnershipRequiresOtherOutAndExactStockAtNoticeCreation() {
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		DefaultWmsOutNoticeService service = new DefaultWmsOutNoticeService();
		ReflectionTestUtils.setField(service, "wmsOutNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", mock(WmsWarehouseBusinessGuard.class));
		WmsOutNoticeDetailEntity line = new WmsOutNoticeDetailEntity()
				.setMatCode("M1").setMatUnit("EA").setNoticeOutNum(1D);
		line.setStockOwnerType("CUSTOMER_SUPPLIED");
		WmsOutNoticeEntity notice = new WmsOutNoticeEntity();
		notice.setWarehouseCode("W1");
		notice.setBillType("other_out");
		notice.setDetailList(List.of(line));

		assertThrows(CheckedException.class, () -> service.saveDeep(notice));
		line.setStockOwnerCode("CUSTOMER-1");
		assertThrows(CheckedException.class, () -> service.saveDeep(notice));
		line.setSourceStockId(91L);
		notice.setBillType("sales");
		assertThrows(CheckedException.class, () -> service.saveDeep(notice));
		verify(notices, never()).insert(any(WmsOutNoticeEntity.class));

		notice.setBillType("other_out");
		service.checkData(notice);
	}

	@Test
	void ownedNoticeCannotClaimAnExternalOwner() {
		DefaultWmsOutNoticeService service = new DefaultWmsOutNoticeService();
		WmsOutNoticeDetailEntity line = new WmsOutNoticeDetailEntity()
				.setMatCode("M1").setMatUnit("EA").setNoticeOutNum(1D);
		line.setStockOwnerCode("CUSTOMER-1");
		WmsOutNoticeEntity notice = new WmsOutNoticeEntity();
		notice.setWarehouseCode("W1");
		notice.setBillType("sales");
		notice.setDetailList(List.of(line));

		assertThrows(CheckedException.class, () -> service.checkData(notice));
	}

	@Test
	void creationChecksSelectedStockIdentityBeforeInsertingNotice() {
		FactoryContextHolder.setFactoryCode("F1");
		TenantContextHolder.setTenantId(1L);
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		WmsLiveStockMapper stocks = mock(WmsLiveStockMapper.class);
		when(stocks.selectById(91L)).thenReturn(externalStock());
		WmsOutNoticeEntity input = externalNotice();
		DefaultWmsOutNoticeService service = new DefaultWmsOutNoticeService();
		ReflectionTestUtils.setField(service, "wmsOutNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsLiveStockMapper", stocks);
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", mock(WmsWarehouseBusinessGuard.class));

		WmsLiveStockEntity selected = externalStock();
		selected.setWarehouseCode("W2");
		when(stocks.selectById(91L)).thenReturn(selected);
		assertThrows(CheckedException.class, () -> service.saveDeep(input));
		selected.setWarehouseCode("W1");
		selected.setStockOwnerCode("OTHER-CUSTOMER");
		assertThrows(CheckedException.class, () -> service.saveDeep(input));
		selected.setStockOwnerCode("CUSTOMER-1");
		selected.setStatus(1);
		assertThrows(CheckedException.class, () -> service.saveDeep(input));
		selected.setStatus(0);
		selected.setMatNum(0.5D);
		assertThrows(CheckedException.class, () -> service.saveDeep(input));
		verify(notices, never()).insert(any(WmsOutNoticeEntity.class));

		selected.setMatNum(2D);
		when(notices.insert(any(WmsOutNoticeEntity.class))).thenAnswer(invocation -> {
			((WmsOutNoticeEntity) invocation.getArgument(0)).setId(7L);
			return 1;
		});
		WmsOutNoticeDetailMapper details = mock(WmsOutNoticeDetailMapper.class);
		when(details.insert(any(WmsOutNoticeDetailEntity.class))).thenReturn(1);
		ReflectionTestUtils.setField(service, "wmsOutNoticeDetailMapper", details);
		assertEquals(Boolean.TRUE, service.saveDeep(input));
	}

	@Test
	void creationRejectsExternalStockWithoutInboundSourceBeforeInsertingNotice() {
		FactoryContextHolder.setFactoryCode("F1");
		TenantContextHolder.setTenantId(1L);
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		WmsLiveStockMapper stocks = mock(WmsLiveStockMapper.class);
		WmsLiveStockEntity selected = externalStock();
		selected.setSourceBusinessNo(null);
		when(stocks.selectById(91L)).thenReturn(selected);
		DefaultWmsOutNoticeService service = new DefaultWmsOutNoticeService();
		ReflectionTestUtils.setField(service, "wmsOutNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsLiveStockMapper", stocks);
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", mock(WmsWarehouseBusinessGuard.class));

		assertThrows(CheckedException.class, () -> service.saveDeep(externalNotice()));
		verify(notices, never()).insert(any(WmsOutNoticeEntity.class));
	}

	@Test
	void editingNoticeRejectsStockFromAnotherTenantBeforeWriting() {
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		WmsOutBillMapper bills = mock(WmsOutBillMapper.class);
		WmsLiveStockMapper stocks = mock(WmsLiveStockMapper.class);
		WmsOutNoticeEntity persisted = externalNotice();
		persisted.setId(7L);
		persisted.setState("0");
		persisted.setTenantId(1L);
		persisted.setFactoryCode("F1");
		when(notices.selectById(7L)).thenReturn(persisted);
		when(bills.selectCount(any())).thenReturn(0L);
		WmsLiveStockEntity selected = externalStock();
		selected.setTenantId(2L);
		when(stocks.selectById(91L)).thenReturn(selected);
		DefaultWmsOutNoticeService service = service(notices, mock(WmsOutNoticeDetailMapper.class), bills,
				mock(WmsWarehouseBusinessGuard.class));
		ReflectionTestUtils.setField(service, "wmsLiveStockMapper", stocks);

		assertThrows(CheckedException.class, () -> service.updateDeep(persisted));
		verify(notices, never()).updateById(any(WmsOutNoticeEntity.class));
	}

	@Test
	void editingDraftCannotBypassDisabledWarehouseBusiness() {
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		WmsOutNoticeDetailMapper details = mock(WmsOutNoticeDetailMapper.class);
		WmsWarehouseBusinessGuard guard = mock(WmsWarehouseBusinessGuard.class);
		WmsOutNoticeEntity persisted = draft();
		when(notices.selectById(7L)).thenReturn(persisted);
		when(notices.updateById(any(WmsOutNoticeEntity.class))).thenReturn(1);
		when(guard.check("W1", "sales", false)).thenThrow(new CheckedException("业务已停用"));
		WmsOutNoticeEntity input = new WmsOutNoticeEntity();
		input.setId(7L);
		input.setWarehouseCode("W1");
		input.setBillType("sales");
		input.setDetailList(List.of(new WmsOutNoticeDetailEntity().setNoticeOutNum(1D)));

		DefaultWmsOutNoticeService service = new DefaultWmsOutNoticeService();
		ReflectionTestUtils.setField(service, "wmsOutNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsOutNoticeDetailMapper", details);
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", guard);

		assertThrows(CheckedException.class, () -> service.updateDeep(input));
	}

	@Test
	void editingDraftDoesNotAcceptClientSuppliedPostingState() {
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		WmsOutNoticeDetailMapper details = mock(WmsOutNoticeDetailMapper.class);
		WmsOutBillMapper bills = mock(WmsOutBillMapper.class);
		WmsWarehouseBusinessGuard guard = mock(WmsWarehouseBusinessGuard.class);
		WmsOutNoticeEntity persisted = draft();
		when(notices.selectById(7L)).thenReturn(persisted);
		when(notices.updateById(any(WmsOutNoticeEntity.class))).thenReturn(1);
		when(details.insert(any(WmsOutNoticeDetailEntity.class))).thenReturn(1);
		when(bills.selectCount(any())).thenReturn(0L);
		when(guard.check("W1", "sales", false)).thenReturn(true);
		WmsOutNoticeEntity input = draft();
		input.setState("3");
		input.setAllOutNum(99D);
		input.setDetailList(List.of(new WmsOutNoticeDetailEntity()
				.setMatCode("M1").setMatUnit("EA").setNoticeOutNum(2D)));

		DefaultWmsOutNoticeService service = service(notices, details, bills, guard);
		assertEquals(Boolean.TRUE, service.updateDeep(input));

		ArgumentCaptor<WmsOutNoticeEntity> saved = ArgumentCaptor.forClass(WmsOutNoticeEntity.class);
		verify(notices).updateById(saved.capture());
		assertEquals("0", saved.getValue().getState());
		assertEquals(0D, saved.getValue().getAllOutNum());
		assertEquals(2D, saved.getValue().getAllNotOutNum());
	}

	@Test
	void replacingDraftDetailsDoesNotReuseForeignDetailIdentity() {
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		WmsOutNoticeDetailMapper details = mock(WmsOutNoticeDetailMapper.class);
		WmsOutNoticeEntity persisted = draft();
		when(notices.selectById(7L)).thenReturn(persisted);
		when(notices.updateById(any(WmsOutNoticeEntity.class))).thenReturn(1);
		when(details.insert(any(WmsOutNoticeDetailEntity.class))).thenReturn(1);
		WmsOutNoticeDetailEntity foreign = new WmsOutNoticeDetailEntity()
				.setNoticeId(8L).setMatCode("M1").setMatUnit("EA").setNoticeOutNum(2D);
		foreign.setId(99L);

		DefaultWmsOutNoticeService service = service(notices, details, mock(WmsOutBillMapper.class),
				mock(WmsWarehouseBusinessGuard.class));
		service.updateDetails(7L, List.of(foreign));
		verify(notices, times(2)).lockNotice(7L);
		assertEquals(2D, persisted.getNoticeOutNum());
		assertEquals(2D, persisted.getAllNotOutNum());

		ArgumentCaptor<WmsOutNoticeDetailEntity> inserted = ArgumentCaptor.forClass(WmsOutNoticeDetailEntity.class);
		verify(details).insert(inserted.capture());
		assertNull(inserted.getValue().getId());
		assertEquals(7L, inserted.getValue().getNoticeId());
	}

	@Test
	void draftWithAnExistingOutboundBillCannotBeDeleted() {
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		WmsOutNoticeDetailMapper details = mock(WmsOutNoticeDetailMapper.class);
		WmsOutBillMapper bills = mock(WmsOutBillMapper.class);
		when(notices.selectList(any())).thenReturn(List.of(draft()));
		when(bills.selectCount(any())).thenReturn(1L);
		DefaultWmsOutNoticeService service = service(notices, details, bills,
				mock(WmsWarehouseBusinessGuard.class));

		assertThrows(CheckedException.class, () -> service.removeDeep(new String[]{"7"}));
	}

	@Test
	void shippedNoticeCannotBeCanceled() {
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		WmsOutNoticeEntity shipped = draft();
		shipped.setState("1");
		shipped.setAllOutNum(2D);
		when(notices.selectById(7L)).thenReturn(shipped);
		DefaultWmsOutNoticeService service = service(notices, mock(WmsOutNoticeDetailMapper.class),
				mock(WmsOutBillMapper.class), mock(WmsWarehouseBusinessGuard.class));

		assertThrows(CheckedException.class, () -> service.voidObjs(7L));
		verify(notices, never()).update(any());
	}

	@Test
	void sourceDeliveryCancellationCannotHidePostedMovement() {
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		WmsOutNoticeEntity shipped = draft();
		shipped.setState("1");
		shipped.setAllOutNum(2D);
		shipped.setDocumentId(88L);
		when(notices.selectList(any())).thenReturn(List.of(shipped));
		when(notices.selectById(7L)).thenReturn(shipped);
		DefaultWmsOutNoticeService service = service(notices, mock(WmsOutNoticeDetailMapper.class),
				mock(WmsOutBillMapper.class), mock(WmsWarehouseBusinessGuard.class));

		assertThrows(CheckedException.class, () -> service.outNoticeRemoveByDuId(88L));
		verify(notices, never()).update(any());
	}

	@Test
	void sourceDeliveryCancellationLocksAndCancelsAnUntouchedNotice() {
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		WmsOutNoticeEntity pending = draft();
		pending.setDocumentId(88L);
		pending.setDocumentTable("wms_deliver_goods_base");
		when(notices.selectList(any())).thenReturn(List.of(pending));
		when(notices.selectById(7L)).thenReturn(pending);
		when(notices.update(any())).thenReturn(1);
		DefaultWmsOutNoticeService service = service(notices, mock(WmsOutNoticeDetailMapper.class),
				mock(WmsOutBillMapper.class), mock(WmsWarehouseBusinessGuard.class));

		assertEquals(Boolean.TRUE, service.voidObjByDocumentId(88L, "wms_deliver_goods_base"));
		verify(notices).lockNotice(7L);
		verify(notices).update(any());
	}

	@Test
	void sourceGeneratedDraftMustBeEditedThroughItsSource() {
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		WmsOutNoticeDetailMapper details = mock(WmsOutNoticeDetailMapper.class);
		WmsOutBillMapper bills = mock(WmsOutBillMapper.class);
		WmsWarehouseBusinessGuard guard = mock(WmsWarehouseBusinessGuard.class);
		WmsOutNoticeEntity sourced = draft();
		sourced.setDocumentId(100L);
		when(notices.selectById(7L)).thenReturn(sourced);
		when(notices.updateById(any(WmsOutNoticeEntity.class))).thenReturn(1);
		when(details.insert(any(WmsOutNoticeDetailEntity.class))).thenReturn(1);
		when(bills.selectCount(any())).thenReturn(0L);
		when(guard.check("W1", "sales", false)).thenReturn(true);
		WmsOutNoticeEntity input = draft();
		input.setDetailList(List.of(new WmsOutNoticeDetailEntity()
				.setMatCode("M1").setMatUnit("EA").setNoticeOutNum(2D)));

		assertThrows(CheckedException.class, () -> service(notices, details, bills, guard).updateDeep(input));
	}

	@Test
	void outboundNoticeRejectsInvalidMaterialQuantityBeforeSaving() {
		DefaultWmsOutNoticeService service = new DefaultWmsOutNoticeService();
		for (double quantity : List.of(-1D, 0D, Double.NaN, Double.POSITIVE_INFINITY)) {
			WmsOutNoticeEntity notice = new WmsOutNoticeEntity();
			notice.setWarehouseCode("W1");
			notice.setBillType("sales");
			notice.setDetailList(List.of(new WmsOutNoticeDetailEntity()
					.setMatCode("M1").setMatUnit("EA").setNoticeOutNum(quantity)));

			assertThrows(CheckedException.class, () -> service.checkData(notice));
		}
	}

	@Test
	void directDetailReplacementCannotIntroduceZeroQuantity() {
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		WmsOutNoticeDetailMapper details = mock(WmsOutNoticeDetailMapper.class);
		when(notices.selectById(7L)).thenReturn(draft());
		when(details.insert(any(WmsOutNoticeDetailEntity.class))).thenReturn(1);
		DefaultWmsOutNoticeService service = service(notices, details, mock(WmsOutBillMapper.class),
				mock(WmsWarehouseBusinessGuard.class));

		assertThrows(CheckedException.class, () -> service.updateDetails(7L, List.of(
				new WmsOutNoticeDetailEntity().setMatCode("M1").setMatUnit("EA").setNoticeOutNum(0D))));
	}

	@Test
	void directDetailReplacementCannotChangeSourceGeneratedNotice() {
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		WmsOutNoticeDetailMapper details = mock(WmsOutNoticeDetailMapper.class);
		WmsOutNoticeEntity sourced = draft();
		sourced.setDocumentId(99L);
		when(notices.selectById(7L)).thenReturn(sourced);
		DefaultWmsOutNoticeService service = service(notices, details,
				mock(WmsOutBillMapper.class), mock(WmsWarehouseBusinessGuard.class));

		assertThrows(CheckedException.class, () -> service.updateDetails(7L, List.of(new WmsOutNoticeDetailEntity()
				.setMatCode("M1").setMatUnit("EA").setNoticeOutNum(1D))));
		verify(details, never()).delete(any());
	}

	@Test
	void newOutboundNoticeAlwaysGetsNewNoticeAndDetailIdentities() {
		FactoryContextHolder.setFactoryCode("F1");
		TenantContextHolder.setTenantId(1L);
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		WmsOutNoticeDetailMapper details = mock(WmsOutNoticeDetailMapper.class);
		WmsWarehouseBusinessGuard guard = mock(WmsWarehouseBusinessGuard.class);
		AtomicReference<Long> headerIdAtInsert = new AtomicReference<>();
		when(guard.check("W1", "sales", false)).thenReturn(true);
		when(notices.insert(any(WmsOutNoticeEntity.class))).thenAnswer(invocation -> {
			WmsOutNoticeEntity inserted = invocation.getArgument(0);
			headerIdAtInsert.set(inserted.getId());
			inserted.setId(7L);
			return 1;
		});
		when(details.insert(any(WmsOutNoticeDetailEntity.class))).thenReturn(1);
		WmsOutNoticeDetailEntity line = new WmsOutNoticeDetailEntity()
				.setMatCode("M1").setMatUnit("EA").setNoticeOutNum(2D);
		line.setId(99L);
		WmsOutNoticeEntity input = draft();
		input.setId(88L);
		input.setFactoryCode("F2");
		input.setTenantId(2L);
		input.setDetailList(List.of(line));
		DefaultWmsOutNoticeService service = service(notices, details, mock(WmsOutBillMapper.class), guard);

		service.saveDeep(input);

		assertNull(headerIdAtInsert.get());
		assertEquals(7L, input.getId());
		assertNull(line.getId());
		assertEquals(7L, line.getNoticeId());
		assertEquals("F1", input.getFactoryCode());
		assertEquals(1L, input.getTenantId());
		assertEquals("F1", line.getFactoryCode());
		assertEquals(1L, line.getTenantId());
	}

	private static WmsOutNoticeEntity draft() {
		WmsOutNoticeEntity notice = new WmsOutNoticeEntity();
		notice.setId(7L);
		notice.setState("0");
		notice.setBillType("sales");
		notice.setWarehouseCode("W1");
		notice.setFactoryCode("F1");
		notice.setTenantId(1L);
		notice.setAllOutNum(0D);
		return notice;
	}

	private static WmsOutNoticeEntity externalNotice() {
		WmsOutNoticeDetailEntity line = new WmsOutNoticeDetailEntity()
				.setMatCode("M1").setMatUnit("EA").setNoticeOutNum(1D);
		line.setStockOwnerType("CUSTOMER_SUPPLIED");
		line.setStockOwnerCode("CUSTOMER-1");
		line.setSourceStockId(91L);
		WmsOutNoticeEntity notice = new WmsOutNoticeEntity();
		notice.setWarehouseCode("W1");
		notice.setBillType("other_out");
		notice.setDetailList(List.of(line));
		return notice;
	}

	private static WmsLiveStockEntity externalStock() {
		WmsLiveStockEntity stock = new WmsLiveStockEntity();
		stock.setId(91L);
		stock.setTenantId(1L);
		stock.setFactoryCode("F1");
		stock.setWarehouseCode("W1");
		stock.setMatCode("M1");
		stock.setMatUnit("EA");
		stock.setMatNum(2D);
		stock.setStatus(0);
		stock.setStockOwnerType("CUSTOMER_SUPPLIED");
		stock.setStockOwnerCode("CUSTOMER-1");
		stock.setSourceBusinessNo("RECEIPT-1");
		return stock;
	}

	private static DefaultWmsOutNoticeService service(WmsOutNoticeMapper notices,
													  WmsOutNoticeDetailMapper details,
													  WmsOutBillMapper bills,
													  WmsWarehouseBusinessGuard guard) {
		DefaultWmsOutNoticeService service = new DefaultWmsOutNoticeService();
		ReflectionTestUtils.setField(service, "wmsOutNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsOutNoticeDetailMapper", details);
		ReflectionTestUtils.setField(service, "wmsOutBillMapper", bills);
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", guard);
		return service;
	}
}
