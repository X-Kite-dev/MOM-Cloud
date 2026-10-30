package com.kitex.mom.wms.service.inNotice;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.entity.WmsInNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.mapper.WmsInBillMapper;
import com.kitex.mom.wms.mapper.WmsInNoticeDetailMapper;
import com.kitex.mom.wms.mapper.WmsInNoticeMapper;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsInNoticeDetailWriteTest {

	@AfterEach
	void clearScope() {
		FactoryContextHolder.clear();
		TenantContextHolder.clear();
	}

	@BeforeAll
	static void registerTable() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "notice-detail-write"),
				WmsInNoticeDetailEntity.class);
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "notice-write"),
				WmsInNoticeEntity.class);
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "notice-bill-write"),
				WmsInBillEntity.class);
	}

	@Test
	void creationRejectsUnconfirmedDetailInsert() {
		WmsInNoticeMapper notices = mock(WmsInNoticeMapper.class);
		WmsInNoticeDetailMapper details = mock(WmsInNoticeDetailMapper.class);
		when(notices.insert(any(WmsInNoticeEntity.class))).thenReturn(1);
		WmsInNoticeDetailEntity line = new WmsInNoticeDetailEntity();
		line.setMatCode("M1");
		line.setMatUnit("EA");
		line.setNoticeInNum(1D);
		WmsInNoticeEntity notice = new WmsInNoticeEntity();
		notice.setWarehouseCode("W1");
		notice.setBillType("purchase");
		notice.setDetailList(List.of(line));
		DefaultWmsInNoticeService service = new DefaultWmsInNoticeService();
		ReflectionTestUtils.setField(service, "wmsInNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsInNoticeDetailMapper", details);
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", mock(WmsWarehouseBusinessGuard.class));

		assertThrows(CheckedException.class, () -> service.saveDeep(notice));
	}

	@Test
	void fractionalNoticeLinesHaveExactTotal() {
		WmsInNoticeMapper notices = mock(WmsInNoticeMapper.class);
		WmsInNoticeDetailMapper details = mock(WmsInNoticeDetailMapper.class);
		when(notices.insert(any(WmsInNoticeEntity.class))).thenReturn(1);
		when(details.insert(any(WmsInNoticeDetailEntity.class))).thenReturn(1);
		WmsInNoticeDetailEntity first = new WmsInNoticeDetailEntity();
		first.setMatCode("M1");
		first.setMatUnit("EA");
		first.setNoticeInNum(0.1D);
		WmsInNoticeDetailEntity second = new WmsInNoticeDetailEntity();
		second.setMatCode("M1");
		second.setMatUnit("EA");
		second.setNoticeInNum(0.2D);
		WmsInNoticeEntity notice = new WmsInNoticeEntity();
		notice.setWarehouseCode("W1");
		notice.setBillType("purchase");
		notice.setDetailList(List.of(first, second));
		DefaultWmsInNoticeService service = new DefaultWmsInNoticeService();
		ReflectionTestUtils.setField(service, "wmsInNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsInNoticeDetailMapper", details);
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", mock(WmsWarehouseBusinessGuard.class));

		service.saveDeep(notice);

		assertEquals(0.3D, notice.getNoticeInNum());
	}

	@Test
	void editingOneNoticeCannotDeleteAnotherNoticesDetails() {
		WmsInNoticeMapper notices = mock(WmsInNoticeMapper.class);
		WmsInNoticeDetailMapper details = mock(WmsInNoticeDetailMapper.class);
		WmsInNoticeEntity notice = new WmsInNoticeEntity();
		notice.setId(7L);
		notice.setState("0");
		notice.setBillType("purchase");
		notice.setWarehouseCode("W1");
		when(notices.selectById(7L)).thenReturn(notice);
		when(notices.updateById(any(WmsInNoticeEntity.class))).thenReturn(1);
		when(details.insert(any(WmsInNoticeDetailEntity.class))).thenReturn(1);

		DefaultWmsInNoticeService service = new DefaultWmsInNoticeService();
		ReflectionTestUtils.setField(service, "wmsInNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsInNoticeDetailMapper", details);
		ReflectionTestUtils.setField(service, "wmsInBillMapper", mock(WmsInBillMapper.class));
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", mock(WmsWarehouseBusinessGuard.class));
		WmsInNoticeDetailEntity line = new WmsInNoticeDetailEntity();
		line.setMatCode("M1");
		line.setMatUnit("EA");
		line.setNoticeInNum(1D);
		service.updateDetails(7L, List.of(line));
		verify(notices, times(2)).lockNotice(7L);
		assertEquals(1D, notice.getNoticeInNum());
		assertEquals(1D, notice.getAllNotInNum());

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Wrapper<WmsInNoticeDetailEntity>> deletion = ArgumentCaptor.forClass(Wrapper.class);
		verify(details).delete(deletion.capture());
		assertTrue(deletion.getValue().getSqlSegment().contains("notice_id"));
		assertTrue(((AbstractWrapper<?, ?, ?>) deletion.getValue()).getParamNameValuePairs().containsValue(7L));
	}

	@Test
	void editingNoticeCannotReuseAnotherNoticesDetailIdentity() {
		WmsInNoticeMapper notices = mock(WmsInNoticeMapper.class);
		WmsInNoticeDetailMapper details = mock(WmsInNoticeDetailMapper.class);
		WmsInNoticeEntity notice = new WmsInNoticeEntity();
		notice.setId(7L);
		notice.setState("0");
		notice.setBillType("purchase");
		notice.setWarehouseCode("W1");
		notice.setFactoryCode("F1");
		when(notices.selectById(7L)).thenReturn(notice);
		when(notices.updateById(any(WmsInNoticeEntity.class))).thenReturn(1);
		when(details.insert(any(WmsInNoticeDetailEntity.class))).thenReturn(1);
		WmsInNoticeDetailEntity submitted = new WmsInNoticeDetailEntity();
		submitted.setId(99L);
		submitted.setNoticeId(8L);
		submitted.setMatCode("M1");
		submitted.setMatUnit("EA");
		submitted.setNoticeInNum(1D);

		DefaultWmsInNoticeService service = new DefaultWmsInNoticeService();
		ReflectionTestUtils.setField(service, "wmsInNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsInNoticeDetailMapper", details);
		ReflectionTestUtils.setField(service, "wmsInBillMapper", mock(WmsInBillMapper.class));
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", mock(WmsWarehouseBusinessGuard.class));
		service.updateDetails(7L, List.of(submitted));
		assertEquals(1D, notice.getAllNotInNum());

		ArgumentCaptor<WmsInNoticeDetailEntity> inserted = ArgumentCaptor.forClass(WmsInNoticeDetailEntity.class);
		verify(details).insert(inserted.capture());
		assertTrue(inserted.getValue().getId() == null);
		assertTrue(Long.valueOf(7L).equals(inserted.getValue().getNoticeId()));
	}

	@Test
	void editingDraftNoticeRecalculatesItsUnreceivedQuantity() {
		WmsInNoticeMapper notices = mock(WmsInNoticeMapper.class);
		WmsInNoticeDetailMapper details = mock(WmsInNoticeDetailMapper.class);
		WmsInBillMapper bills = mock(WmsInBillMapper.class);
		WmsWarehouseBusinessGuard guard = mock(WmsWarehouseBusinessGuard.class);
		WmsInNoticeEntity persisted = new WmsInNoticeEntity();
		persisted.setId(7L);
		persisted.setState("0");
		persisted.setBillType("purchase");
		persisted.setWarehouseCode("W1");
		persisted.setFactoryCode("F1");
		persisted.setTenantId(1L);
		persisted.setAllInNum(0D);
		when(notices.selectById(7L)).thenReturn(persisted);
		when(notices.updateById(any(WmsInNoticeEntity.class))).thenReturn(1);
		when(details.insert(any(WmsInNoticeDetailEntity.class))).thenReturn(1);
		when(bills.selectCount(any())).thenReturn(0L);
		when(guard.check("W1", "purchase", true)).thenReturn(true);
		WmsInNoticeDetailEntity line = new WmsInNoticeDetailEntity();
		line.setMatCode("M1");
		line.setMatUnit("EA");
		line.setNoticeInNum(5D);
		WmsInNoticeEntity input = new WmsInNoticeEntity();
		input.setId(7L);
		input.setWarehouseCode("W1");
		input.setWarehouseName("Main");
		input.setBillType("purchase");
		input.setDetailList(List.of(line));

		DefaultWmsInNoticeService service = new DefaultWmsInNoticeService();
		ReflectionTestUtils.setField(service, "wmsInNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsInNoticeDetailMapper", details);
		ReflectionTestUtils.setField(service, "wmsInBillMapper", bills);
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", guard);

		assertEquals(Boolean.TRUE, service.updateDeep(input));
		assertEquals(5D, persisted.getNoticeInNum());
		assertEquals(5D, persisted.getAllNotInNum());
		assertEquals(5D, line.getNotInNum());
	}

	@Test
	void completedReceiptNoticeMustRemainAsInventoryEvidence() {
		WmsInNoticeMapper notices = mock(WmsInNoticeMapper.class);
		WmsInNoticeDetailMapper details = mock(WmsInNoticeDetailMapper.class);
		WmsInNoticeEntity completed = new WmsInNoticeEntity();
		completed.setId(7L);
		completed.setState("2");
		completed.setAllInNum(5D);
		when(notices.selectList(any())).thenReturn(List.of(completed));
		DefaultWmsInNoticeService service = new DefaultWmsInNoticeService();
		ReflectionTestUtils.setField(service, "wmsInNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsInNoticeDetailMapper", details);

		assertThrows(CheckedException.class, () -> service.removeDeep(new String[]{"7"}));
	}

	@Test
	void receivedNoticeCannotBeCanceled() {
		WmsInNoticeMapper notices = mock(WmsInNoticeMapper.class);
		WmsInNoticeEntity received = new WmsInNoticeEntity();
		received.setId(7L);
		received.setState("1");
		received.setAllInNum(2D);
		when(notices.selectById(7L)).thenReturn(received);
		DefaultWmsInNoticeService service = new DefaultWmsInNoticeService();
		ReflectionTestUtils.setField(service, "wmsInNoticeMapper", notices);

		assertThrows(CheckedException.class, () -> service.voidObjs(7L));
		verify(notices, never()).update(any());
	}

	@Test
	void draftWithAnExistingInboundBillCannotBeDeleted() {
		WmsInNoticeMapper notices = mock(WmsInNoticeMapper.class);
		WmsInNoticeDetailMapper details = mock(WmsInNoticeDetailMapper.class);
		WmsInBillMapper bills = mock(WmsInBillMapper.class);
		WmsInNoticeEntity draft = new WmsInNoticeEntity();
		draft.setId(7L);
		draft.setState("0");
		when(notices.selectList(any())).thenReturn(List.of(draft));
		when(bills.selectCount(any())).thenReturn(1L);
		DefaultWmsInNoticeService service = new DefaultWmsInNoticeService();
		ReflectionTestUtils.setField(service, "wmsInNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsInNoticeDetailMapper", details);
		ReflectionTestUtils.setField(service, "wmsInBillMapper", bills);

		assertThrows(CheckedException.class, () -> service.removeDeep(new String[]{"7"}));
	}

	@Test
	void inboundNoticeRejectsInvalidMaterialQuantityBeforeSaving() {
		DefaultWmsInNoticeService service = new DefaultWmsInNoticeService();
		for (double quantity : List.of(-1D, 0D, Double.NaN, Double.POSITIVE_INFINITY)) {
			WmsInNoticeDetailEntity line = new WmsInNoticeDetailEntity();
			line.setMatCode("M1");
			line.setMatUnit("EA");
			line.setNoticeInNum(quantity);
			WmsInNoticeEntity notice = new WmsInNoticeEntity();
			notice.setWarehouseCode("W1");
			notice.setBillType("purchase");
			notice.setDetailList(List.of(line));

			assertThrows(CheckedException.class, () -> service.checkData(notice));
		}
	}

	@Test
	void inboundNoticeRejectsExternalOwnershipWithoutSourceOrWrongBusinessType() {
		WmsInNoticeDetailEntity line = new WmsInNoticeDetailEntity();
		line.setMatCode("M1");
		line.setMatUnit("EA");
		line.setNoticeInNum(1D);
		line.setStockOwnerType("CUSTOMER_SUPPLIED");
		line.setStockOwnerCode("CUSTOMER-1");
		WmsInNoticeEntity notice = new WmsInNoticeEntity();
		notice.setWarehouseCode("W1");
		notice.setBillType("other_in");
		notice.setDetailList(List.of(line));
		DefaultWmsInNoticeService service = new DefaultWmsInNoticeService();

		assertThrows(CheckedException.class, () -> service.checkData(notice));
		line.setSourceBusinessNo("ORDER-1");
		notice.setBillType("purchase");
		assertThrows(CheckedException.class, () -> service.checkData(notice));
	}

	@Test
	void directDetailReplacementCannotIntroduceZeroQuantity() {
		WmsInNoticeMapper notices = mock(WmsInNoticeMapper.class);
		WmsInNoticeDetailMapper details = mock(WmsInNoticeDetailMapper.class);
		WmsInNoticeEntity notice = new WmsInNoticeEntity();
		notice.setId(7L);
		notice.setState("0");
		when(notices.selectById(7L)).thenReturn(notice);
		when(details.insert(any(WmsInNoticeDetailEntity.class))).thenReturn(1);
		WmsInNoticeDetailEntity line = new WmsInNoticeDetailEntity();
		line.setMatCode("M1");
		line.setMatUnit("EA");
		line.setNoticeInNum(0D);
		DefaultWmsInNoticeService service = new DefaultWmsInNoticeService();
		ReflectionTestUtils.setField(service, "wmsInNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsInNoticeDetailMapper", details);

		assertThrows(CheckedException.class, () -> service.updateDetails(7L, List.of(line)));
	}

	@Test
	void directDetailReplacementCannotChangeSourceGeneratedNotice() {
		WmsInNoticeMapper notices = mock(WmsInNoticeMapper.class);
		WmsInNoticeDetailMapper details = mock(WmsInNoticeDetailMapper.class);
		WmsInNoticeEntity notice = new WmsInNoticeEntity();
		notice.setId(7L);
		notice.setState("0");
		notice.setDocumentId(99L);
		notice.setBillType("purchase");
		notice.setWarehouseCode("W1");
		when(notices.selectById(7L)).thenReturn(notice);
		DefaultWmsInNoticeService service = new DefaultWmsInNoticeService();
		ReflectionTestUtils.setField(service, "wmsInNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsInNoticeDetailMapper", details);
		WmsInNoticeDetailEntity line = new WmsInNoticeDetailEntity();
		line.setMatCode("M1");
		line.setMatUnit("EA");
		line.setNoticeInNum(1D);

		assertThrows(IllegalArgumentException.class, () -> service.updateDetails(7L, List.of(line)));
		verify(details, never()).delete(any());
	}

	@Test
	void readingNoticeDoesNotInventAnUnpersistedBatch() {
		WmsInNoticeMapper notices = mock(WmsInNoticeMapper.class);
		WmsInNoticeDetailMapper details = mock(WmsInNoticeDetailMapper.class);
		WmsInNoticeEntity notice = new WmsInNoticeEntity();
		notice.setId(7L);
		WmsInNoticeDetailEntity line = new WmsInNoticeDetailEntity();
		line.setNoticeId(7L);
		line.setMatValidPeriod(30);
		when(notices.selectById(7L)).thenReturn(notice);
		when(details.selectList(any())).thenReturn(List.of(line));
		DefaultWmsInNoticeService service = new DefaultWmsInNoticeService();
		ReflectionTestUtils.setField(service, "wmsInNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsInNoticeDetailMapper", details);

		assertNull(service.selectById(7L).getDetailList().get(0).getMatBatch());
	}

	@Test
	void newInboundNoticeAlwaysGetsNewNoticeAndDetailIdentities() {
		FactoryContextHolder.setFactoryCode("F1");
		TenantContextHolder.setTenantId(1L);
		WmsInNoticeMapper notices = mock(WmsInNoticeMapper.class);
		WmsInNoticeDetailMapper details = mock(WmsInNoticeDetailMapper.class);
		WmsWarehouseBusinessGuard guard = mock(WmsWarehouseBusinessGuard.class);
		AtomicReference<Long> headerIdAtInsert = new AtomicReference<>();
		when(guard.check("W1", "purchase", true)).thenReturn(true);
		when(notices.insert(any(WmsInNoticeEntity.class))).thenAnswer(invocation -> {
			WmsInNoticeEntity inserted = invocation.getArgument(0);
			headerIdAtInsert.set(inserted.getId());
			inserted.setId(7L);
			return 1;
		});
		when(details.insert(any(WmsInNoticeDetailEntity.class))).thenReturn(1);
		WmsInNoticeDetailEntity line = new WmsInNoticeDetailEntity();
		line.setId(99L);
		line.setMatCode("M1");
		line.setMatUnit("EA");
		line.setNoticeInNum(2D);
		WmsInNoticeEntity input = new WmsInNoticeEntity();
		input.setId(88L);
		input.setWarehouseCode("W1");
		input.setBillType("purchase");
		input.setFactoryCode("F2");
		input.setTenantId(2L);
		input.setDetailList(List.of(line));
		DefaultWmsInNoticeService service = new DefaultWmsInNoticeService();
		ReflectionTestUtils.setField(service, "wmsInNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsInNoticeDetailMapper", details);
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", guard);

		service.saveDeep(input);

		assertEquals(null, headerIdAtInsert.get());
		assertEquals(7L, input.getId());
		assertEquals(7L, line.getNoticeId());
		assertNull(line.getId());
		assertEquals("F1", input.getFactoryCode());
		assertEquals(1L, input.getTenantId());
		assertEquals("F1", line.getFactoryCode());
		assertEquals(1L, line.getTenantId());
	}
}
