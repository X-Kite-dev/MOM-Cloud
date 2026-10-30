package com.kitex.mom.wms.service.outBill;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.entity.WmsOutBillDetailEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import com.kitex.mom.wms.mapper.WmsOutBillMapper;
import com.kitex.mom.wms.mapper.WmsOutNoticeDetailMapper;
import com.kitex.mom.wms.mapper.WmsOutNoticeMapper;
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

class WmsOutBillNoticePostingTest {

	@BeforeAll
	static void registerTables() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "out-posting-notice"),
				WmsOutNoticeEntity.class);
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "out-posting-detail"),
				WmsOutNoticeDetailEntity.class);
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "out-posting-bill"),
				WmsOutBillEntity.class);
	}

	@Test
	void canceledNoticeCannotIssueStock() {
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		WmsOutNoticeDetailMapper details = mock(WmsOutNoticeDetailMapper.class);
		WmsOutNoticeEntity notice = new WmsOutNoticeEntity();
		notice.setId(1L);
		notice.setState("3");
		notice.setAllOutNum(0D);
		notice.setAllNotOutNum(5D);
		WmsOutNoticeDetailEntity line = new WmsOutNoticeDetailEntity();
		line.setId(7L);
		line.setNoticeId(1L);
		line.setMatCode("M1");
		line.setOutNum(0D);
		line.setNotOutNum(5D);
		when(notices.selectById(1L)).thenReturn(notice);
		when(details.selectList(any())).thenReturn(List.of(line));
		when(details.update(any())).thenReturn(1);
		when(notices.update(any())).thenReturn(1);
		WmsOutBillDetailEntity billLine = new WmsOutBillDetailEntity();
		billLine.setNoticeDetailId(7L);
		billLine.setMatCode("M1");
		billLine.setMatNum(2D);
		WmsOutBillEntity bill = new WmsOutBillEntity();
		bill.setNoticeId(1L);
		bill.setDetails(List.of(billLine));
		DefaultWmsOutBillService service = new DefaultWmsOutBillService();
		ReflectionTestUtils.setField(service, "wmsOutNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsOutNoticeDetailMapper", details);

		assertThrows(CheckedException.class, () -> service.createBillHandleNoticeOutNum(bill));
		verify(notices).lockNotice(1L);
	}

	@Test
	void noticeDetailCannotBeConsumedByAnotherMaterial() {
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		WmsOutNoticeDetailMapper details = mock(WmsOutNoticeDetailMapper.class);
		WmsOutNoticeEntity notice = new WmsOutNoticeEntity();
		notice.setId(1L);
		notice.setState("0");
		notice.setAllOutNum(0D);
		notice.setAllNotOutNum(5D);
		WmsOutNoticeDetailEntity line = new WmsOutNoticeDetailEntity();
		line.setId(7L);
		line.setNoticeId(1L);
		line.setMatCode("M1");
		line.setOutNum(0D);
		line.setNotOutNum(5D);
		when(notices.selectById(1L)).thenReturn(notice);
		when(details.selectList(any())).thenReturn(List.of(line));
		when(details.update(any())).thenReturn(1);
		when(notices.update(any())).thenReturn(1);
		WmsOutBillDetailEntity billLine = new WmsOutBillDetailEntity();
		billLine.setNoticeDetailId(7L);
		billLine.setMatCode("M2");
		billLine.setMatNum(2D);
		WmsOutBillEntity bill = new WmsOutBillEntity();
		bill.setNoticeId(1L);
		bill.setDetails(List.of(billLine));
		DefaultWmsOutBillService service = new DefaultWmsOutBillService();
		ReflectionTestUtils.setField(service, "wmsOutNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsOutNoticeDetailMapper", details);

		assertThrows(CheckedException.class, () -> service.createBillHandleNoticeOutNum(bill));
	}

	@Test
	void controlledNoticeCannotSwitchOwnershipOrLockedStock() {
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		WmsOutNoticeDetailMapper details = mock(WmsOutNoticeDetailMapper.class);
		WmsOutNoticeEntity notice = new WmsOutNoticeEntity();
		notice.setId(1L);
		notice.setState("0");
		notice.setWarehouseCode("W1");
		notice.setBillType("other_out");
		notice.setAllOutNum(0D);
		notice.setAllNotOutNum(5D);
		WmsOutNoticeDetailEntity line = new WmsOutNoticeDetailEntity();
		line.setId(7L);
		line.setMatCode("M1");
		line.setStockOwnerType("CUSTOMER_SUPPLIED");
		line.setStockOwnerCode("CUSTOMER-1");
		line.setSourceStockId(11L);
		line.setOutNum(0D);
		line.setNotOutNum(5D);
		when(notices.selectById(1L)).thenReturn(notice);
		when(details.selectList(any())).thenReturn(List.of(line));
		WmsOutBillDetailEntity billLine = new WmsOutBillDetailEntity();
		billLine.setNoticeDetailId(7L);
		billLine.setMatCode("M1");
		billLine.setMatNum(2D);
		billLine.setStockOwnerType("CUSTOMER_SUPPLIED");
		billLine.setStockOwnerCode("CUSTOMER-1");
		billLine.setSourceStockId(12L);
		WmsOutBillEntity bill = new WmsOutBillEntity();
		bill.setNoticeId(1L);
		bill.setWarehouseCode("W1");
		bill.setBillType("other_out");
		bill.setDetails(List.of(billLine));
		DefaultWmsOutBillService service = new DefaultWmsOutBillService();
		ReflectionTestUtils.setField(service, "wmsOutNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsOutNoticeDetailMapper", details);

		assertThrows(CheckedException.class, () -> service.createBillHandleNoticeOutNum(bill));
		billLine.setSourceStockId(11L);
		billLine.setStockOwnerCode("CUSTOMER-2");
		assertThrows(CheckedException.class, () -> service.createBillHandleNoticeOutNum(bill));
		verify(details, never()).update(any());
	}

	@Test
	void nonPositiveQuantityCannotOccupyNotice() {
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		WmsOutNoticeDetailMapper details = mock(WmsOutNoticeDetailMapper.class);
		WmsOutNoticeEntity notice = new WmsOutNoticeEntity();
		notice.setId(1L);
		notice.setState("0");
		notice.setAllOutNum(0D);
		notice.setAllNotOutNum(5D);
		WmsOutNoticeDetailEntity line = new WmsOutNoticeDetailEntity();
		line.setId(7L);
		line.setMatCode("M1");
		line.setOutNum(0D);
		line.setNotOutNum(5D);
		when(notices.selectById(1L)).thenReturn(notice);
		when(details.selectList(any())).thenReturn(List.of(line));
		when(details.update(any())).thenReturn(1);
		when(notices.update(any())).thenReturn(1);
		WmsOutBillDetailEntity billLine = new WmsOutBillDetailEntity();
		billLine.setNoticeDetailId(7L);
		billLine.setMatCode("M1");
		billLine.setMatNum(-2D);
		WmsOutBillEntity bill = new WmsOutBillEntity();
		bill.setNoticeId(1L);
		bill.setDetails(List.of(billLine));
		DefaultWmsOutBillService service = new DefaultWmsOutBillService();
		ReflectionTestUtils.setField(service, "wmsOutNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsOutNoticeDetailMapper", details);

		assertThrows(CheckedException.class, () -> service.createBillHandleNoticeOutNum(bill));
	}

	@Test
	void fractionalQuantitiesDoNotCreateRoundingShortage() {
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		WmsOutNoticeDetailMapper details = mock(WmsOutNoticeDetailMapper.class);
		WmsOutNoticeEntity notice = new WmsOutNoticeEntity();
		notice.setId(1L);
		notice.setState("0");
		notice.setAllOutNum(0D);
		notice.setAllNotOutNum(0.3D);
		WmsOutNoticeDetailEntity line = new WmsOutNoticeDetailEntity();
		line.setId(7L);
		line.setMatCode("M1");
		line.setOutNum(0D);
		line.setNotOutNum(0.3D);
		when(notices.selectById(1L)).thenReturn(notice);
		when(details.selectList(any())).thenReturn(List.of(line));
		when(details.update(any())).thenReturn(1);
		when(notices.update(any())).thenReturn(1);
		WmsOutBillDetailEntity first = new WmsOutBillDetailEntity();
		first.setNoticeDetailId(7L);
		first.setMatCode("M1");
		first.setMatNum(0.1D);
		WmsOutBillDetailEntity second = new WmsOutBillDetailEntity();
		second.setNoticeDetailId(7L);
		second.setMatCode("M1");
		second.setMatNum(0.2D);
		WmsOutBillEntity bill = new WmsOutBillEntity();
		bill.setNoticeId(1L);
		bill.setDetails(List.of(first, second));
		DefaultWmsOutBillService service = new DefaultWmsOutBillService();
		ReflectionTestUtils.setField(service, "wmsOutNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsOutNoticeDetailMapper", details);

		assertEquals(0D, service.createBillHandleNoticeOutNum(bill));
		assertEquals(0D, line.getNotOutNum());
		assertEquals(0.3D, line.getOutNum());
	}

	@Test
	void missingNoticeLineAbortsRollback() {
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		WmsOutNoticeDetailMapper details = mock(WmsOutNoticeDetailMapper.class);
		WmsOutNoticeEntity notice = new WmsOutNoticeEntity();
		notice.setId(1L);
		notice.setAllOutNum(2D);
		notice.setAllNotOutNum(3D);
		when(notices.selectById(1L)).thenReturn(notice);
		when(notices.update(any())).thenReturn(1);
		WmsOutBillDetailEntity line = new WmsOutBillDetailEntity();
		line.setNoticeDetailId(7L);
		line.setMatNum(2D);
		WmsOutBillEntity bill = new WmsOutBillEntity();
		bill.setNoticeId(1L);
		bill.setDetails(List.of(line));
		DefaultWmsOutBillService service = new DefaultWmsOutBillService();
		ReflectionTestUtils.setField(service, "wmsOutNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsOutNoticeDetailMapper", details);

		assertThrows(CheckedException.class, () -> service.removeBillHandleNoticeOutNum(bill));
	}

	@Test
	void removingTwoBillLinesRestoresBothQuantitiesOnOneNoticeLine() {
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		WmsOutNoticeDetailMapper details = mock(WmsOutNoticeDetailMapper.class);
		WmsOutNoticeEntity notice = new WmsOutNoticeEntity();
		notice.setId(1L);
		notice.setAllOutNum(0.3D);
		notice.setAllNotOutNum(0D);
		WmsOutNoticeDetailEntity line = new WmsOutNoticeDetailEntity();
		line.setId(7L);
		line.setMatCode("M1");
		line.setOutNum(0.3D);
		line.setNotOutNum(0D);
		when(notices.selectById(1L)).thenReturn(notice);
		when(details.selectList(any())).thenReturn(List.of(line));
		when(details.update(any())).thenReturn(1);
		when(notices.update(any())).thenReturn(1);
		WmsOutBillDetailEntity first = new WmsOutBillDetailEntity();
		first.setNoticeDetailId(7L);
		first.setMatCode("M1");
		first.setMatNum(0.1D);
		WmsOutBillDetailEntity second = new WmsOutBillDetailEntity();
		second.setNoticeDetailId(7L);
		second.setMatCode("M1");
		second.setMatNum(0.2D);
		WmsOutBillEntity bill = new WmsOutBillEntity();
		bill.setNoticeId(1L);
		bill.setDetails(List.of(first, second));
		DefaultWmsOutBillService service = new DefaultWmsOutBillService();
		ReflectionTestUtils.setField(service, "wmsOutNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsOutNoticeDetailMapper", details);

		service.removeBillHandleNoticeOutNum(bill);

		assertEquals(0.3D, line.getNotOutNum());
		assertEquals(0D, line.getOutNum());
		verify(notices).lockNotice(1L);
	}

	@Test
	void completingNoticeLocksBeforeCheckingOpenBills() {
		WmsOutNoticeMapper notices = mock(WmsOutNoticeMapper.class);
		WmsOutBillMapper bills = mock(WmsOutBillMapper.class);
		WmsOutNoticeEntity notice = new WmsOutNoticeEntity();
		notice.setId(1L);
		notice.setAllNotOutNum(0D);
		when(notices.selectById(1L)).thenReturn(notice);
		when(notices.update(any())).thenReturn(1);
		when(bills.selectList(any())).thenReturn(List.of());
		WmsOutBillEntity bill = new WmsOutBillEntity();
		bill.setNoticeId(1L);
		DefaultWmsOutBillService service = new DefaultWmsOutBillService();
		ReflectionTestUtils.setField(service, "wmsOutNoticeMapper", notices);
		ReflectionTestUtils.setField(service, "wmsOutBillMapper", bills);

		service.passBillHandleNoticeState(bill);

		InOrder order = inOrder(notices, bills);
		order.verify(notices).lockNotice(1L);
		order.verify(bills).selectList(any());
	}
}
