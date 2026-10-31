package com.kitex.mom.wms.service.inBill;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.dto.app.WmsInBillDetailAppDTO;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.entity.WmsIdentifierPolicyEntity;
import com.kitex.mom.wms.entity.WmsInNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.mapper.WmsInBillDetailMapper;
import com.kitex.mom.wms.mapper.WmsInBillMapper;
import com.kitex.mom.wms.mapper.WmsInNoticeDetailMapper;
import com.kitex.mom.wms.mapper.WmsInNoticeMapper;
import com.kitex.mom.wms.service.WmsIdentifierPolicyService;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class WmsMobileInboundBoundaryTest {

	@BeforeAll
	static void registerTables() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "mobile-in-notice"),
				WmsInNoticeEntity.class);
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "mobile-in-detail"),
				WmsInNoticeDetailEntity.class);
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "mobile-in-bill"),
				WmsInBillEntity.class);
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "mobile-in-bill-detail"),
				WmsInBillDetailEntity.class);
	}

	@AfterEach
	void clearContext() {
		FactoryContextHolder.clear();
		TenantContextHolder.clear();
	}

	@Test
	void invalidQuantityFailsBeforeReadingNotice() {
		DefaultWmsInBillService service = new DefaultWmsInBillService();
		WmsInBillDetailAppDTO command = command();
		command.setMatNum(-1D);

		assertThrows(CheckedException.class, () -> service.setInBillNum(command));
	}

	@Test
	void explicitUnknownBillNumberCannotCreateReplacement() {
		PostingCase posting = postingCase();
		WmsInBillDetailAppDTO command = command();
		command.setBillNo("IN-MISSING");
		when(posting.bills().selectList(any())).thenReturn(List.of());

		assertThrows(CheckedException.class, () -> posting.service().setInBillNum(command));
		InOrder order = inOrder(posting.notices());
		order.verify(posting.notices()).lockNotice(1L);
		order.verify(posting.notices()).selectById(1L);
	}

	@Test
	void ambiguousNoticeDetailsRequireExactSelection() {
		PostingCase posting = postingCase();
		WmsInNoticeDetailEntity other = new WmsInNoticeDetailEntity();
		other.setId(8L);
		other.setNoticeId(1L);
		other.setMatCode("M1");
		other.setMatUnit("EA");
		other.setNotInNum(5D);
		other.setInNum(0D);
		when(posting.details().selectList(any())).thenReturn(List.of(posting.line(), other));

		assertThrows(CheckedException.class, () -> posting.service().setInBillNum(command()));
	}

	@Test
	void validContinuationOccupiesNoticeAndReturnsOriginalBillNumber() {
		PostingCase posting = postingCase();
		WmsInBillEntity bill = existingBill();
		when(posting.bills().selectList(any())).thenReturn(List.of(bill));
		when(posting.billDetails().selectList(any())).thenReturn(List.of());
		when(posting.billDetails().insert(any(WmsInBillDetailEntity.class))).thenReturn(1);
		when(posting.bills().update(any())).thenReturn(1);
		when(posting.details().update(any())).thenReturn(1);
		when(posting.notices().update(any())).thenReturn(1);

		assertEquals("IN-1", posting.service().setInBillNum(command()));
	}

	@Test
	void differentScannedContainerCreatesASeparateInboundDetail() {
		PostingCase posting = continuationWith(oldDetail("C1"));

		assertEquals("IN-1", posting.service().setInBillNum(command().setContainerCode("C2")));

		ArgumentCaptor<WmsInBillDetailEntity> saved = ArgumentCaptor.forClass(WmsInBillDetailEntity.class);
		verify(posting.billDetails()).insert(saved.capture());
		assertEquals("C2", saved.getValue().getContainerCode());
		assertEquals(2D, saved.getValue().getMatNum());
		verify(posting.billDetails(), never()).updateById(any(WmsInBillDetailEntity.class));
	}

	@Test
	void existingContainerRowsCanAccumulateOnlyIntoTheScannedContainer() {
		var first = oldDetail("C1");
		var second = oldDetail("C2");
		second.setId(22L);
		PostingCase posting = continuationWith(first, second);

		assertEquals("IN-1", posting.service().setInBillNum(command().setContainerCode("C2")));

		ArgumentCaptor<WmsInBillDetailEntity> saved = ArgumentCaptor.forClass(WmsInBillDetailEntity.class);
		verify(posting.billDetails()).updateById(saved.capture());
		assertEquals(22L, saved.getValue().getId());
		assertEquals("C2", saved.getValue().getContainerCode());
		assertEquals(1D, first.getMatNum());
		verify(posting.billDetails(), never()).insert(any(WmsInBillDetailEntity.class));
	}

	@Test
	void serialScanCannotBeRepeatedInAnotherContainerOfTheSameInboundBill() {
		PostingCase posting = continuationWith(oldDetail("C1").setMatBarcode("SN1"));
		var identifiers = (WmsIdentifierPolicyService) ReflectionTestUtils.getField(posting.service(), "identifierPolicyService");
		when(identifiers.resolveEffective("purchase", "M1"))
				.thenReturn(new WmsIdentifierPolicyEntity().setSerialManaged(true));

		assertThrows(CheckedException.class, () -> posting.service().setInBillNum(
				command().setContainerCode("C2").setMatBarcode("SN1").setMatNum(1D)));

		verify(posting.billDetails(), never()).insert(any(WmsInBillDetailEntity.class));
		verify(posting.billDetails(), never()).updateById(any(WmsInBillDetailEntity.class));
	}

	@Test
	void finalMergedInboundPlacementIsRecheckedBeforeUpdatingOrOccupyingNotice() {
		PostingCase posting = continuationWith(oldDetail("C1"));
		failAfterEntryPlacement(posting);

		assertThrows(CheckedException.class, () -> posting.service().setInBillNum(command().setContainerCode("C1")));

		verify(posting.billDetails(), never()).updateById(any(WmsInBillDetailEntity.class));
		verify(posting.details(), never()).update(any());
		verify(posting.notices(), never()).update(any());
	}

	@Test
	void finalNewInboundPlacementIsRecheckedBeforeIdentifiersOrInsert() {
		PostingCase posting = continuationWith();
		failAfterEntryPlacement(posting);

		assertThrows(CheckedException.class, () -> posting.service().setInBillNum(command().setContainerCode("C2")));

		var identifiers = (WmsIdentifierPolicyService) ReflectionTestUtils.getField(posting.service(), "identifierPolicyService");
		verify(identifiers, never()).fillMissingIdentifiers(any(), any());
		verify(posting.billDetails(), never()).insert(any(WmsInBillDetailEntity.class));
		verify(posting.details(), never()).update(any());
		verify(posting.notices(), never()).update(any());
	}

	private static PostingCase continuationWith(WmsInBillDetailEntity... oldRows) {
		PostingCase posting = postingCase();
		when(posting.bills().selectList(any())).thenReturn(List.of(existingBill()));
		when(posting.billDetails().selectList(any())).thenReturn(List.of(oldRows));
		when(posting.billDetails().insert(any(WmsInBillDetailEntity.class))).thenReturn(1);
		when(posting.billDetails().updateById(any(WmsInBillDetailEntity.class))).thenReturn(1);
		when(posting.bills().update(any())).thenReturn(1);
		when(posting.details().update(any())).thenReturn(1);
		when(posting.notices().update(any())).thenReturn(1);
		return posting;
	}

	private static WmsInBillDetailEntity oldDetail(String container) {
		var row = new WmsInBillDetailEntity().setWarehouseCode("W1").setSiteCode("S1")
				.setContainerCode(container).setMatCode("M1").setMatUnit("EA").setMatNum(1D)
				.setBillId(10L).setNoticeDetailId(7L);
		row.setTenantId(1L);
		row.setFactoryCode("F1");
		return row;
	}

	private static void failAfterEntryPlacement(PostingCase posting) {
		var guard = (WmsWarehouseBusinessGuard) ReflectionTestUtils.getField(posting.service(), "warehouseBusinessGuard");
		var reads = new java.util.concurrent.atomic.AtomicInteger();
		doAnswer(call -> {
			if (reads.incrementAndGet() > 1) {
				throw new CheckedException("最终放置范围已失效");
			}
			return null;
		}).when(guard).checkPlacements(eq("W1"), anyList());
	}

	@Test
	void failedDetailInsertAbortsContinuation() {
		PostingCase posting = postingCase();
		when(posting.bills().selectList(any())).thenReturn(List.of(existingBill()));
		when(posting.billDetails().selectList(any())).thenReturn(List.of());

		assertThrows(CheckedException.class, () -> posting.service().setInBillNum(command()));
	}

	@Test
	void exactNoticeLineSeparatesTheSameMaterialByOwnership() {
		PostingCase posting = postingCase();
		posting.notice().setBillType("other_in");
		posting.line().setStockOwnerType("CONSIGNED");
		posting.line().setStockOwnerCode("SUP-1");
		posting.line().setSourceBusinessNo("CONSIGN-1");
		WmsInBillEntity bill = existingBill();
		bill.setBillType("other_in");
		when(posting.bills().selectList(any())).thenReturn(List.of(bill));
		when(posting.details().selectList(any())).thenAnswer(invocation -> {
			LambdaQueryWrapper<WmsInNoticeDetailEntity> query = invocation.getArgument(0);
			query.getSqlSegment();
			if (query.getParamNameValuePairs().containsValue(7L)) {
				return List.of(posting.line());
			}
			WmsInNoticeDetailEntity other = new WmsInNoticeDetailEntity();
			other.setMatCode("M1");
			return List.of(posting.line(), other);
		});
		when(posting.billDetails().selectList(any())).thenReturn(List.of());
		when(posting.billDetails().insert(any(WmsInBillDetailEntity.class))).thenReturn(1);
		when(posting.bills().update(any())).thenReturn(1);
		when(posting.details().update(any())).thenReturn(1);
		when(posting.notices().update(any())).thenReturn(1);

		assertEquals("IN-1", posting.service().setInBillNum(command().setNoticeDetailId(7L)));

		ArgumentCaptor<WmsInBillDetailEntity> saved = ArgumentCaptor.forClass(WmsInBillDetailEntity.class);
		verify(posting.billDetails()).insert(saved.capture());
		assertEquals(7L, saved.getValue().getNoticeDetailId());
		assertEquals("CONSIGNED", saved.getValue().getStockOwnerType());
		assertEquals("SUP-1", saved.getValue().getStockOwnerCode());
		assertEquals("CONSIGN-1", saved.getValue().getSourceBusinessNo());
	}

	@Test
	void selectedNoticeLineMustMatchTheReturnedSource() {
		PostingCase posting = postingCase();

		assertThrows(CheckedException.class,
				() -> posting.service().setInBillNum(command().setNoticeDetailId(8L)));
		verify(posting.billDetails(), never()).insert(any(WmsInBillDetailEntity.class));
		verify(posting.bills(), never()).selectList(any());
	}

	@Test
	void nonpositiveNoticeLineFailsBeforeReadingTheNotice() {
		DefaultWmsInBillService service = new DefaultWmsInBillService();

		assertThrows(CheckedException.class, () -> service.setInBillNum(command().setNoticeDetailId(0L)));
	}

	@Test
	void continuationCannotAccumulateIntoADifferentOwnershipIdentity() {
		PostingCase posting = postingCase();
		posting.notice().setBillType("other_in");
		posting.line().setStockOwnerType("CUSTOMER_SUPPLIED");
		posting.line().setStockOwnerCode("CUSTOMER-1");
		posting.line().setSourceBusinessNo("CUSTOMER-ORDER-1");
		WmsInBillEntity bill = existingBill();
		bill.setBillType("other_in");
		WmsInBillDetailEntity detail = new WmsInBillDetailEntity().setStockOwnerType("OWNED")
				.setWarehouseCode("W1").setMatNum(1D).setMatUnit("EA");
		detail.setTenantId(1L);
		detail.setFactoryCode("F1");
		when(posting.bills().selectList(any())).thenReturn(List.of(bill));
		when(posting.billDetails().selectList(any())).thenReturn(List.of(detail));

		assertThrows(CheckedException.class, () -> posting.service().setInBillNum(command()));
		verify(posting.billDetails(), never()).updateById(any(WmsInBillDetailEntity.class));
	}

	private static PostingCase postingCase() {
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
		WmsInNoticeMapper notices = mock(WmsInNoticeMapper.class);
		WmsInNoticeDetailMapper details = mock(WmsInNoticeDetailMapper.class);
		WmsInBillMapper bills = mock(WmsInBillMapper.class);
		WmsInBillDetailMapper billDetails = mock(WmsInBillDetailMapper.class);
		WmsWarehouseBusinessGuard guard = mock(WmsWarehouseBusinessGuard.class);
		WmsInNoticeEntity notice = new WmsInNoticeEntity();
		notice.setId(1L);
		notice.setTenantId(1L);
		notice.setFactoryCode("F1");
		notice.setWarehouseCode("W1");
		notice.setBillType("purchase");
		notice.setState("0");
		notice.setAllNotInNum(5D);
		notice.setAllInNum(0D);
		WmsInNoticeDetailEntity line = new WmsInNoticeDetailEntity();
		line.setId(7L);
		line.setNoticeId(1L);
		line.setTenantId(1L);
		line.setFactoryCode("F1");
		line.setWarehouseCode("W1");
		line.setMatCode("M1");
		line.setMatUnit("EA");
		line.setNotInNum(5D);
		line.setInNum(0D);
		when(notices.selectById(1L)).thenReturn(notice);
		when(details.selectList(any())).thenReturn(List.of(line));
		when(guard.check("W1", "purchase", true)).thenReturn(true);
		when(guard.check("W1", "other_in", true)).thenReturn(true);
		DefaultWmsInBillService service = spy(new DefaultWmsInBillService());
		doReturn(true).when(service).checkLocationOfWarehouse("W1", "S1");
		ReflectionTestUtils.setField(service, "wmsInNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsInNoticeDetailMapper", details);
		ReflectionTestUtils.setField(service, "wmsInBillMapper", bills);
		ReflectionTestUtils.setField(service, "wmsInBillDetailMapper", billDetails);
		ReflectionTestUtils.setField(service, "identifierPolicyService", mock(WmsIdentifierPolicyService.class));
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", guard);
		return new PostingCase(service, notices, details, bills, billDetails, line, notice);
	}

	private static WmsInBillEntity existingBill() {
		WmsInBillEntity bill = new WmsInBillEntity();
		bill.setId(10L);
		bill.setBillNo("IN-1");
		bill.setNoticeId(1L);
		bill.setTenantId(1L);
		bill.setFactoryCode("F1");
		bill.setWarehouseCode("W1");
		bill.setBillType("purchase");
		bill.setSource("app");
		bill.setState("11");
		bill.setDetailNotFinishNum(0D);
		return bill;
	}

	private static WmsInBillDetailAppDTO command() {
		return new WmsInBillDetailAppDTO()
				.setNoticeId(1L)
				.setMatCode("M1")
				.setMatNum(2D)
				.setSiteCode("S1");
	}

	private record PostingCase(DefaultWmsInBillService service, WmsInNoticeMapper notices,
	                           WmsInNoticeDetailMapper details, WmsInBillMapper bills,
	                           WmsInBillDetailMapper billDetails, WmsInNoticeDetailEntity line,
	                           WmsInNoticeEntity notice) {
	}
}
