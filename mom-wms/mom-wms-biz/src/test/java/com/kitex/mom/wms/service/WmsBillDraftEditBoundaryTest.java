package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.entity.WmsOutBillDetailEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.mapper.WmsInBillDetailMapper;
import com.kitex.mom.wms.mapper.WmsInBillMapper;
import com.kitex.mom.wms.mapper.WmsOutBillDetailMapper;
import com.kitex.mom.wms.mapper.WmsOutBillMapper;
import com.kitex.mom.wms.service.inBill.DefaultWmsInBillService;
import com.kitex.mom.wms.service.outBill.DefaultWmsOutBillService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsBillDraftEditBoundaryTest {

	@BeforeAll
	static void registerTables() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "draft-in-bill"),
				WmsInBillEntity.class);
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "draft-in-detail"),
				WmsInBillDetailEntity.class);
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "draft-out-bill"),
				WmsOutBillEntity.class);
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "draft-out-detail"),
				WmsOutBillDetailEntity.class);
	}

	@Test
	void inboundDraftEditKeepsPostingStateAndSource() {
		WmsInBillMapper bills = mock(WmsInBillMapper.class);
		WmsInBillDetailMapper details = mock(WmsInBillDetailMapper.class);
		WmsIdentifierPolicyService identifiers = mock(WmsIdentifierPolicyService.class);
		WmsWarehouseBusinessGuard guard = mock(WmsWarehouseBusinessGuard.class);
		WmsInBillEntity persisted = new WmsInBillEntity();
		persisted.setId(7L);
		persisted.setState("0");
		persisted.setBillType("purchase");
		persisted.setWarehouseCode("W1");
		when(bills.selectById(7L)).thenReturn(persisted);
		when(bills.update(any())).thenReturn(1);
		when(details.insert(any(WmsInBillDetailEntity.class))).thenReturn(1);
		when(guard.check("W1", "purchase", true)).thenReturn(true);
		WmsInBillEntity input = new WmsInBillEntity();
		input.setId(7L);
		input.setState("2");
		input.setBillType("purchase");
		input.setNoticeId(99L);
		input.setWarehouseCode("W1");
		input.setDetails(List.of(new WmsInBillDetailEntity()
				.setMatCode("M1").setMatUnit("EA").setMatNum(1D)));
		DefaultWmsInBillService service = new DefaultWmsInBillService();
		ReflectionTestUtils.setField(service, "wmsInBillMapper", bills);
		ReflectionTestUtils.setField(service, "wmsInBillDetailMapper", details);
		ReflectionTestUtils.setField(service, "identifierPolicyService", identifiers);
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", guard);

		service.updateDeep(input);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<LambdaUpdateWrapper<WmsInBillEntity>> saved = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
		verify(bills).update(saved.capture());
		assertFalse(saved.getValue().getSqlSet().contains("state"));
		assertFalse(saved.getValue().getSqlSet().contains("notice_id"));
		assertTrue(saved.getValue().getSqlSegment().contains("state"));
		assertNull(persisted.getNoticeId());
	}

	@Test
	void outboundDraftEditKeepsPostingStateAndSource() {
		WmsOutBillMapper bills = mock(WmsOutBillMapper.class);
		WmsOutBillDetailMapper details = mock(WmsOutBillDetailMapper.class);
		WmsWarehouseBusinessGuard guard = mock(WmsWarehouseBusinessGuard.class);
		WmsOutBillEntity persisted = new WmsOutBillEntity();
		persisted.setId(7L);
		persisted.setState("0");
		persisted.setBillType("sales");
		persisted.setWarehouseCode("W1");
		when(bills.selectById(7L)).thenReturn(persisted);
		when(bills.update(any())).thenReturn(1);
		when(details.insert(any(WmsOutBillDetailEntity.class))).thenReturn(1);
		when(guard.check("W1", "sales", false)).thenReturn(true);
		WmsOutBillEntity input = new WmsOutBillEntity();
		input.setId(7L);
		input.setState("2");
		input.setBillType("sales");
		input.setNoticeId(99L);
		input.setWarehouseCode("W1");
		input.setDetails(List.of(new WmsOutBillDetailEntity()
				.setMatCode("M1").setMatUnit("EA").setMatNum(1D)));
		DefaultWmsOutBillService service = new DefaultWmsOutBillService();
		ReflectionTestUtils.setField(service, "wmsOutBillMapper", bills);
		ReflectionTestUtils.setField(service, "wmsOutBillDetailMapper", details);
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", guard);

		service.updateDeep(input);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<LambdaUpdateWrapper<WmsOutBillEntity>> saved = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
		verify(bills).update(saved.capture());
		assertFalse(saved.getValue().getSqlSet().contains("state"));
		assertFalse(saved.getValue().getSqlSet().contains("notice_id"));
		assertTrue(saved.getValue().getSqlSegment().contains("state"));
		assertNull(persisted.getNoticeId());
	}

	@Test
	void directInboundDetailEditChecksWarehouseBusinessBeforeReplacingRows() {
		WmsInBillMapper bills = mock(WmsInBillMapper.class);
		WmsInBillDetailMapper details = mock(WmsInBillDetailMapper.class);
		WmsWarehouseBusinessGuard guard = mock(WmsWarehouseBusinessGuard.class);
		WmsInBillEntity draft = new WmsInBillEntity();
		draft.setId(7L);
		draft.setState("0");
		draft.setBillType("purchase");
		draft.setWarehouseCode("W1");
		when(bills.selectById(7L)).thenReturn(draft);
		when(guard.check("W1", "purchase", true)).thenThrow(new CheckedException("业务已停用"));
		DefaultWmsInBillService service = new DefaultWmsInBillService();
		ReflectionTestUtils.setField(service, "wmsInBillMapper", bills);
		ReflectionTestUtils.setField(service, "wmsInBillDetailMapper", details);
		ReflectionTestUtils.setField(service, "identifierPolicyService", mock(WmsIdentifierPolicyService.class));
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", guard);

		assertThrows(CheckedException.class, () -> service.updateDetails(7L,
				List.of(new WmsInBillDetailEntity().setMatCode("M1"))));
		verify(details, never()).delete(any());
	}

	@Test
	void directOutboundDetailEditChecksWarehouseBusinessBeforeReplacingRows() {
		WmsOutBillMapper bills = mock(WmsOutBillMapper.class);
		WmsOutBillDetailMapper details = mock(WmsOutBillDetailMapper.class);
		WmsWarehouseBusinessGuard guard = mock(WmsWarehouseBusinessGuard.class);
		WmsOutBillEntity draft = new WmsOutBillEntity();
		draft.setId(7L);
		draft.setState("0");
		draft.setBillType("sales");
		draft.setWarehouseCode("W1");
		when(bills.selectById(7L)).thenReturn(draft);
		when(guard.check("W1", "sales", false)).thenThrow(new CheckedException("业务已停用"));
		DefaultWmsOutBillService service = new DefaultWmsOutBillService();
		ReflectionTestUtils.setField(service, "wmsOutBillMapper", bills);
		ReflectionTestUtils.setField(service, "wmsOutBillDetailMapper", details);
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", guard);

		assertThrows(CheckedException.class, () -> service.updateDetails(7L,
				List.of(new WmsOutBillDetailEntity().setMatCode("M1"))));
		verify(details, never()).delete(any());
	}

	@Test
	void inboundCommitRequiresConfirmedStateTransition() {
		WmsInBillMapper bills = mock(WmsInBillMapper.class);
		WmsInBillDetailMapper details = mock(WmsInBillDetailMapper.class);
		WmsWarehouseBusinessGuard guard = mock(WmsWarehouseBusinessGuard.class);
		WmsInBillEntity draft = new WmsInBillEntity();
		draft.setId(7L);
		draft.setState("0");
		draft.setWarehouseCode("W1");
		draft.setBillType("other_in");
		when(bills.selectById(7L)).thenReturn(draft);
		when(details.selectList(any())).thenReturn(List.of(new WmsInBillDetailEntity().setSiteCode("S1")));
		when(guard.check("W1", "other_in", true)).thenReturn(true);
		DefaultWmsInBillService service = new DefaultWmsInBillService();
		ReflectionTestUtils.setField(service, "wmsInBillMapper", bills);
		ReflectionTestUtils.setField(service, "wmsInBillDetailMapper", details);
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", guard);

		assertThrows(CheckedException.class, () -> service.commitBill(7L));
		verify(bills).update(any());
	}

	@Test
	void outboundCommitRequiresConfirmedStateTransition() {
		WmsOutBillMapper bills = mock(WmsOutBillMapper.class);
		WmsOutBillDetailMapper details = mock(WmsOutBillDetailMapper.class);
		WmsWarehouseBusinessGuard guard = mock(WmsWarehouseBusinessGuard.class);
		WmsOutBillEntity draft = new WmsOutBillEntity();
		draft.setId(7L);
		draft.setState("0");
		draft.setWarehouseCode("W1");
		draft.setBillType("other_out");
		when(bills.selectById(7L)).thenReturn(draft);
		when(details.selectList(any())).thenReturn(List.of(new WmsOutBillDetailEntity().setSiteCode("S1")));
		when(guard.check("W1", "other_out", false)).thenReturn(true);
		DefaultWmsOutBillService service = new DefaultWmsOutBillService();
		ReflectionTestUtils.setField(service, "wmsOutBillMapper", bills);
		ReflectionTestUtils.setField(service, "wmsOutBillDetailMapper", details);
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", guard);

		assertThrows(CheckedException.class, () -> service.commitBill(7L));
		verify(bills).update(any());
	}

	@Test
	void inboundReviewRejectsBillWithoutDetails() {
		WmsInBillMapper bills = mock(WmsInBillMapper.class);
		WmsInBillDetailMapper details = mock(WmsInBillDetailMapper.class);
		WmsWarehouseBusinessGuard guard = mock(WmsWarehouseBusinessGuard.class);
		WmsInBillEntity bill = new WmsInBillEntity();
		bill.setId(7L);
		bill.setState("1");
		bill.setBillType("purchase");
		bill.setWarehouseCode("W1");
		when(bills.selectOne(any())).thenReturn(bill);
		when(details.selectList(any())).thenReturn(List.of());
		when(guard.check("W1", "purchase", true)).thenReturn(true);
		DefaultWmsInBillService service = new DefaultWmsInBillService();
		ReflectionTestUtils.setField(service, "wmsInBillMapper", bills);
		ReflectionTestUtils.setField(service, "wmsInBillDetailMapper", details);
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", guard);

		assertThrows(CheckedException.class, () -> service.passBill(7L));
		verify(bills, never()).update(any());
	}

	@Test
	void outboundReviewRejectsBillWithoutDetails() {
		WmsOutBillMapper bills = mock(WmsOutBillMapper.class);
		WmsOutBillDetailMapper details = mock(WmsOutBillDetailMapper.class);
		WmsWarehouseBusinessGuard guard = mock(WmsWarehouseBusinessGuard.class);
		WmsOutBillEntity bill = new WmsOutBillEntity();
		bill.setId(7L);
		bill.setState("1");
		bill.setBillType("sales");
		bill.setWarehouseCode("W1");
		when(bills.selectOne(any())).thenReturn(bill);
		when(details.selectList(any())).thenReturn(List.of());
		when(guard.check("W1", "sales", false)).thenReturn(true);
		DefaultWmsOutBillService service = new DefaultWmsOutBillService();
		ReflectionTestUtils.setField(service, "wmsOutBillMapper", bills);
		ReflectionTestUtils.setField(service, "wmsOutBillDetailMapper", details);
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", guard);

		assertThrows(CheckedException.class, () -> service.passBill(7L));
		verify(bills, never()).update(any());
	}

	@Test
	void inboundCreationRejectsUnconfirmedDetailInsert() {
		WmsInBillMapper bills = mock(WmsInBillMapper.class);
		WmsInBillDetailMapper details = mock(WmsInBillDetailMapper.class);
		when(bills.insert(any(WmsInBillEntity.class))).thenReturn(1);
		WmsInBillEntity bill = new WmsInBillEntity();
		bill.setDetails(List.of(new WmsInBillDetailEntity().setMatCode("M1")));
		DefaultWmsInBillService service = new DefaultWmsInBillService();
		ReflectionTestUtils.setField(service, "wmsInBillMapper", bills);
		ReflectionTestUtils.setField(service, "wmsInBillDetailMapper", details);

		assertThrows(CheckedException.class, () -> service.doSaveDeep(bill));
	}

	@Test
	void inboundDraftReplacementRejectsUnconfirmedDetailInsert() {
		WmsInBillMapper bills = mock(WmsInBillMapper.class);
		WmsInBillDetailMapper details = mock(WmsInBillDetailMapper.class);
		WmsWarehouseBusinessGuard guard = mock(WmsWarehouseBusinessGuard.class);
		WmsInBillEntity bill = new WmsInBillEntity();
		bill.setId(7L);
		bill.setState("0");
		bill.setBillType("purchase");
		bill.setWarehouseCode("W1");
		when(bills.selectById(7L)).thenReturn(bill);
		when(guard.check("W1", "purchase", true)).thenReturn(true);
		DefaultWmsInBillService service = new DefaultWmsInBillService();
		ReflectionTestUtils.setField(service, "wmsInBillMapper", bills);
		ReflectionTestUtils.setField(service, "wmsInBillDetailMapper", details);
		ReflectionTestUtils.setField(service, "identifierPolicyService", mock(WmsIdentifierPolicyService.class));
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", guard);

		assertThrows(CheckedException.class, () -> service.updateDetails(7L,
				List.of(new WmsInBillDetailEntity().setMatCode("M1"))));
	}

	@Test
	void outboundDraftReplacementRejectsUnconfirmedDetailInsert() {
		WmsOutBillMapper bills = mock(WmsOutBillMapper.class);
		WmsOutBillDetailMapper details = mock(WmsOutBillDetailMapper.class);
		WmsWarehouseBusinessGuard guard = mock(WmsWarehouseBusinessGuard.class);
		WmsOutBillEntity bill = new WmsOutBillEntity();
		bill.setId(7L);
		bill.setState("0");
		bill.setBillType("sales");
		bill.setWarehouseCode("W1");
		when(bills.selectById(7L)).thenReturn(bill);
		when(guard.check("W1", "sales", false)).thenReturn(true);
		DefaultWmsOutBillService service = new DefaultWmsOutBillService();
		ReflectionTestUtils.setField(service, "wmsOutBillMapper", bills);
		ReflectionTestUtils.setField(service, "wmsOutBillDetailMapper", details);
		ReflectionTestUtils.setField(service, "warehouseBusinessGuard", guard);

		assertThrows(CheckedException.class, () -> service.updateDetails(7L,
				List.of(new WmsOutBillDetailEntity().setMatCode("M1"))));
	}

	@Test
	void noticeLinkedInboundBillCannotReplaceReservedDetails() {
		WmsInBillMapper bills = mock(WmsInBillMapper.class);
		WmsInBillDetailMapper details = mock(WmsInBillDetailMapper.class);
		WmsInBillEntity linked = new WmsInBillEntity();
		linked.setId(7L);
		linked.setState("0");
		linked.setNoticeId(11L);
		when(bills.selectById(7L)).thenReturn(linked);
		DefaultWmsInBillService service = new DefaultWmsInBillService();
		ReflectionTestUtils.setField(service, "wmsInBillMapper", bills);
		ReflectionTestUtils.setField(service, "wmsInBillDetailMapper", details);
		WmsInBillEntity input = new WmsInBillEntity();
		input.setId(7L);
		input.setDetails(List.of(new WmsInBillDetailEntity().setMatCode("M2")));

		assertThrows(CheckedException.class, () -> service.updateDeep(input));
		assertThrows(CheckedException.class, () -> service.updateDetails(7L, input.getDetails()));
		verify(details, never()).delete(any());
	}

	@Test
	void noticeLinkedOutboundBillCannotReplaceReservedDetails() {
		WmsOutBillMapper bills = mock(WmsOutBillMapper.class);
		WmsOutBillDetailMapper details = mock(WmsOutBillDetailMapper.class);
		WmsOutBillEntity linked = new WmsOutBillEntity();
		linked.setId(7L);
		linked.setState("0");
		linked.setNoticeId(11L);
		when(bills.selectById(7L)).thenReturn(linked);
		DefaultWmsOutBillService service = new DefaultWmsOutBillService();
		ReflectionTestUtils.setField(service, "wmsOutBillMapper", bills);
		ReflectionTestUtils.setField(service, "wmsOutBillDetailMapper", details);
		WmsOutBillEntity input = new WmsOutBillEntity();
		input.setId(7L);
		input.setDetails(List.of(new WmsOutBillDetailEntity().setMatCode("M2")));

		assertThrows(CheckedException.class, () -> service.updateDeep(input));
		assertThrows(CheckedException.class, () -> service.updateDetails(7L, input.getDetails()));
		verify(details, never()).delete(any());
	}
}
