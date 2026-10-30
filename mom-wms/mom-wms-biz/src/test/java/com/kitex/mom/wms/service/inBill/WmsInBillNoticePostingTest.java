package com.kitex.mom.wms.service.inBill;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.entity.WmsInNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.mapper.WmsInBillMapper;
import com.kitex.mom.wms.mapper.WmsInNoticeDetailMapper;
import com.kitex.mom.wms.mapper.WmsInNoticeMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsInBillNoticePostingTest {

	@BeforeAll
	static void registerTables() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "posting-notice"),
				WmsInNoticeEntity.class);
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "posting-detail"),
				WmsInNoticeDetailEntity.class);
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "posting-bill"),
				WmsInBillEntity.class);
	}

	@Test
	void failedNoticeDetailUpdateAbortsPosting() {
		PostingCase posting = postingCase();

		assertThrows(CheckedException.class, () -> posting.service().createBillHandleNoticeInNum(posting.bill()));
		verify(posting.notices()).lockNotice(1L);
	}

	@Test
	void failedNoticeTotalUpdateAbortsPosting() {
		PostingCase posting = postingCase();
		when(posting.details().update(any())).thenReturn(1);

		assertThrows(CheckedException.class, () -> posting.service().createBillHandleNoticeInNum(posting.bill()));
		verify(posting.notices()).lockNotice(1L);
	}

	@Test
	void canceledNoticeCannotReceiveStock() {
		PostingCase posting = postingCase();
		posting.notice().setState("3");
		when(posting.details().update(any())).thenReturn(1);
		when(posting.notices().update(any())).thenReturn(1);

		assertThrows(CheckedException.class, () -> posting.service().createBillHandleNoticeInNum(posting.bill()));
	}

	@Test
	void noticeDetailCannotBeConsumedByAnotherMaterial() {
		PostingCase posting = postingCase();
		posting.bill().getDetails().get(0).setMatCode("M2");
		when(posting.details().update(any())).thenReturn(1);
		when(posting.notices().update(any())).thenReturn(1);

		assertThrows(CheckedException.class, () -> posting.service().createBillHandleNoticeInNum(posting.bill()));
	}

	@Test
	void externalNoticeOwnershipCannotBecomeCompanyOwnedOnReceipt() {
		PostingCase posting = postingCase();
		posting.line().setStockOwnerType("CUSTOMER_SUPPLIED");
		posting.line().setStockOwnerCode("CUSTOMER-1");
		posting.line().setSourceBusinessNo("ORDER-1");
		posting.bill().getDetails().get(0).setStockOwnerType("OWNED");

		assertThrows(CheckedException.class, () -> posting.service().createBillHandleNoticeInNum(posting.bill()));
		verify(posting.details(), never()).update(any());
	}

	@Test
	void externalNoticeSourceCannotChangeOnReceipt() {
		PostingCase posting = postingCase();
		posting.line().setStockOwnerType("CONSIGNED");
		posting.line().setStockOwnerCode("SUPPLIER-1");
		posting.line().setSourceBusinessNo("ORDER-1");
		posting.bill().getDetails().get(0).setStockOwnerType("CONSIGNED");
		posting.bill().getDetails().get(0).setStockOwnerCode("SUPPLIER-1");
		posting.bill().getDetails().get(0).setSourceBusinessNo("ORDER-2");

		assertThrows(CheckedException.class, () -> posting.service().createBillHandleNoticeInNum(posting.bill()));
		verify(posting.details(), never()).update(any());
	}

	@Test
	void nonPositiveQuantityCannotOccupyNotice() {
		PostingCase posting = postingCase();
		posting.bill().getDetails().get(0).setMatNum(-2D);
		when(posting.details().update(any())).thenReturn(1);
		when(posting.notices().update(any())).thenReturn(1);

		assertThrows(CheckedException.class, () -> posting.service().createBillHandleNoticeInNum(posting.bill()));
	}

	@Test
	void fractionalQuantitiesDoNotCreateRoundingShortage() {
		PostingCase posting = postingCase();
		posting.line().setNotInNum(0.3D);
		posting.notice().setAllNotInNum(0.3D);
		posting.bill().getDetails().get(0).setMatNum(0.1D);
		WmsInBillDetailEntity second = new WmsInBillDetailEntity();
		second.setNoticeDetailId(7L);
		second.setMatCode("M1");
		second.setMatNum(0.2D);
		posting.bill().setDetails(List.of(posting.bill().getDetails().get(0), second));
		when(posting.details().update(any())).thenReturn(1);
		when(posting.notices().update(any())).thenReturn(1);

		assertEquals(0D, posting.service().createBillHandleNoticeInNum(posting.bill()));
		assertEquals(0D, posting.line().getNotInNum());
		assertEquals(0.3D, posting.line().getInNum());
	}

	@Test
	void failedNoticeDetailRollbackAbortsDeletion() {
		PostingCase posting = postingCase();
		posting.line().setInNum(2D);
		posting.line().setNotInNum(3D);
		posting.notice().setAllInNum(2D);
		posting.notice().setAllNotInNum(3D);

		assertThrows(CheckedException.class, () -> posting.service().removeBillHandleNoticeInNum(posting.bill()));
	}

	@Test
	void removingTwoBillLinesRestoresBothQuantitiesOnOneNoticeLine() {
		PostingCase posting = postingCase();
		WmsInNoticeDetailEntity line = posting.line();
		line.setInNum(0.3D);
		line.setNotInNum(0D);
		posting.notice().setAllInNum(0.3D);
		posting.notice().setAllNotInNum(0D);
		posting.bill().getDetails().get(0).setMatNum(0.1D);
		WmsInBillDetailEntity second = new WmsInBillDetailEntity();
		second.setNoticeDetailId(7L);
		second.setMatCode("M1");
		second.setMatNum(0.2D);
		posting.bill().setDetails(List.of(posting.bill().getDetails().get(0), second));
		when(posting.details().update(any())).thenReturn(1);
		when(posting.notices().update(any())).thenReturn(1);

		posting.service().removeBillHandleNoticeInNum(posting.bill());

		assertEquals(0.3D, line.getNotInNum());
		assertEquals(0D, line.getInNum());
		verify(posting.notices()).lockNotice(1L);
	}

	@Test
	void missingNoticeLineAbortsRollback() {
		PostingCase posting = postingCase();
		when(posting.details().selectList(any())).thenReturn(List.of());
		when(posting.notices().update(any())).thenReturn(1);

		assertThrows(CheckedException.class, () -> posting.service().removeBillHandleNoticeInNum(posting.bill()));
	}

	@Test
	void completingNoticeLocksBeforeCheckingOpenBills() {
		PostingCase posting = postingCase();
		WmsInBillMapper bills = mock(WmsInBillMapper.class);
		posting.notice().setAllNotInNum(0D);
		when(bills.selectList(any())).thenReturn(List.of());
		when(posting.notices().update(any())).thenReturn(1);
		ReflectionTestUtils.setField(posting.service(), "wmsInBillMapper", bills);

		posting.service().passBillHandleNoticeState(posting.bill());

		InOrder order = inOrder(posting.notices(), bills);
		order.verify(posting.notices()).lockNotice(1L);
		order.verify(bills).selectList(any());
	}

	private static PostingCase postingCase() {
		WmsInNoticeMapper notices = mock(WmsInNoticeMapper.class);
		WmsInNoticeDetailMapper details = mock(WmsInNoticeDetailMapper.class);
		WmsInNoticeEntity notice = new WmsInNoticeEntity();
		notice.setId(1L);
		notice.setState("0");
		notice.setAllInNum(0D);
		notice.setAllNotInNum(5D);
		WmsInNoticeDetailEntity line = new WmsInNoticeDetailEntity();
		line.setId(7L);
		line.setNoticeId(1L);
		line.setMatCode("M1");
		line.setInNum(0D);
		line.setNotInNum(5D);
		when(notices.selectById(1L)).thenReturn(notice);
		when(details.selectList(any())).thenReturn(List.of(line));
		WmsInBillDetailEntity billLine = new WmsInBillDetailEntity();
		billLine.setNoticeDetailId(7L);
		billLine.setMatCode("M1");
		billLine.setMatNum(2D);
		WmsInBillEntity bill = new WmsInBillEntity();
		bill.setNoticeId(1L);
		bill.setDetails(List.of(billLine));
		DefaultWmsInBillService service = new DefaultWmsInBillService();
		ReflectionTestUtils.setField(service, "wmsInNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsInNoticeDetailMapper", details);
		return new PostingCase(service, notices, details, notice, line, bill);
	}

	private record PostingCase(DefaultWmsInBillService service, WmsInNoticeMapper notices,
			WmsInNoticeDetailMapper details, WmsInNoticeEntity notice,
			WmsInNoticeDetailEntity line, WmsInBillEntity bill) {
	}
}
