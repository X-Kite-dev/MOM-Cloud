package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.wms.dto.WmsInventoryAdjustmentApprovalReview;
import com.kitex.mom.wms.dto.WmsInventoryAdjustmentApprovalSubmit;
import com.kitex.mom.wms.dto.WmsInventoryAdjustmentCommand;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanEntity;
import com.kitex.mom.wms.entity.WmsInventoryAdjustmentApprovalEntity;
import com.kitex.mom.wms.mapper.WmsInventoryAdjustmentApprovalMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WmsInventoryAdjustmentApprovalServiceTest {
	private final WmsInventoryPlanAccess access = mock(WmsInventoryPlanAccess.class);
	private final WmsInventoryAdjustmentApprovalMapper mapper = mock(WmsInventoryAdjustmentApprovalMapper.class);
	private final com.kitex.mom.wms.service.WmsWarehouseBusinessGuard mobileGuard = mock(com.kitex.mom.wms.service.WmsWarehouseBusinessGuard.class);
	private final WmsInventoryAdjustmentApprovalService service =
			new WmsInventoryAdjustmentApprovalService(access, mapper, mobileGuard);

	@BeforeEach
	void setUp() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "approval-test"),
				WmsInventoryAdjustmentApprovalEntity.class);
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
		actor("counter");
		when(access.detail(2L)).thenReturn(detail());
		when(access.plan(1L, true)).thenReturn(plan());
		when(mapper.insert(any(WmsInventoryAdjustmentApprovalEntity.class))).thenAnswer(call -> {
			WmsInventoryAdjustmentApprovalEntity row = call.getArgument(0);
			row.setId(10L);
			return 1;
		});
		when(mapper.updateById(any(WmsInventoryAdjustmentApprovalEntity.class))).thenReturn(1);
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
	}

	@Test
	void newMobileApprovalCannotWriteWhenTheActualPlanWarehouseIsDisabled() {
		doThrow(new CheckedException("mobile disabled")).when(mobileGuard).checkMobile("W1");

		assertThrows(CheckedException.class, () -> service.submitMobile(submit()));

		verify(mapper, never()).insert(any(WmsInventoryAdjustmentApprovalEntity.class));
	}

	@Test
	void sameApprovalRequestCanReturnItsOriginalRecordAfterMobileIsDisabled() {
		var original = service.submit(submit());
		when(mapper.selectOne(any())).thenReturn(original);
		doThrow(new CheckedException("mobile disabled")).when(mobileGuard).checkMobile("W1");

		assertSame(original, service.submitMobile(submit()));

		verify(mobileGuard, never()).checkMobile("W1");
	}

	@Test
	void mobileReviewCannotChangeAValidPendingApprovalWhenDisabled() {
		var original = service.submit(submit());
		when(mapper.selectOne(any())).thenReturn(original);
		actor("supervisor");
		doThrow(new CheckedException("mobile disabled")).when(mobileGuard).checkMobile("W1");

		assertThrows(CheckedException.class, () -> service.reviewMobile(review()));

		verify(mapper, never()).updateById(any(WmsInventoryAdjustmentApprovalEntity.class));
		assertEquals("PENDING", original.getStatus());
	}

	@Test
	void requiresDifferentReviewerThenPostsOnlyFrozenApprovedSelection() {
		when(mapper.selectOne(any())).thenReturn(null, null);
		WmsInventoryAdjustmentApprovalEntity submitted = service.submit(submit());
		assertEquals("PENDING", submitted.getStatus());
		assertEquals("counter", submitted.getApplicantCode());

		when(mapper.selectOne(any())).thenReturn(submitted);
		var review = review();
		assertThrows(CheckedException.class, () -> service.review(review));
		actor("supervisor");
		WmsInventoryAdjustmentApprovalEntity approved = service.review(review);
		assertEquals("APPROVED", approved.getStatus());
		assertEquals("supervisor", approved.getReviewerCode());

		WmsInventoryAdjustmentCommand changed = adjustment();
		changed.getDetail().setMatBatch("OTHER");
		assertThrows(CheckedException.class, () -> service.requireApproved(detail(), changed));
		assertSame(approved, service.requireApproved(detail(), adjustment()));
		service.markPosted(approved);
		assertEquals("POSTED", approved.getStatus());
		assertNotNull(approved.getPostedTime());
	}

	@Test
	void directAdjustmentWithoutAnApprovedRequestIsRejected() {
		when(mapper.selectOne(any())).thenReturn(null);
		assertThrows(CheckedException.class, () -> service.requireApproved(detail(), adjustment()));
	}

	@Test
	void approvedDifferenceCanBeRevokedBeforePostingAndRecounted() {
		var approved = new WmsInventoryAdjustmentApprovalEntity();
		approved.setId(10L);
		approved.setPlanId(1L);
		approved.setPlanDetailId(2L);
		approved.setWarehouseCode("W1");
		approved.setApplicantCode("counter");
		approved.setStatus("APPROVED");
		when(mapper.selectOne(any())).thenReturn(approved);
		actor("supervisor");
		WmsInventoryAdjustmentApprovalReview command = review();
		command.setDecision("REVOKE");
		command.setComment("原库存已变化，重新复盘");

		assertEquals("REVOKED", service.review(command).getStatus());
		assertEquals("原库存已变化，重新复盘", approved.getRevokeReason());
		assertNotNull(approved.getRevokedTime());
		assertDoesNotThrow(() -> service.requireNoActive(2L));
		assertThrows(CheckedException.class, () -> service.requireApproved(detail(), adjustment()));
	}

	@Test
	void changedCountCannotBeSubmittedForApproval() {
		WmsInventoryAdjustmentApprovalSubmit command = submit();
		command.setExpectedInventoryNum(8d);
		assertThrows(CheckedException.class, () -> service.submit(command));
		verify(mapper, never()).insert(any(WmsInventoryAdjustmentApprovalEntity.class));
	}

	@Test
	void approvalSnapshotCanRoundTripThroughBrowserJsonWithoutLosingNullAttributes() {
		when(mapper.selectOne(any())).thenReturn(null);
		WmsInventoryAdjustmentApprovalEntity submitted = service.submit(submit());
		String snapshot = submitted.getSelectedDetailJson();
		assertTrue(snapshot.contains("\"matProduceDate\":\"2026-10-01\""), snapshot);
		assertTrue(snapshot.contains("\"containerCode\":null"), snapshot);
	}

	private WmsInventoryAdjustmentApprovalSubmit submit() {
		var command = new WmsInventoryAdjustmentApprovalSubmit();
		command.setRequestId("count-approval-0001");
		command.setPlanDetailId(2L);
		command.setExpectedCurrentNumber(10d);
		command.setExpectedInventoryNum(7d);
		command.setDetail(selection());
		command.setReason("复盘确认盘亏");
		return command;
	}

	private WmsInventoryAdjustmentApprovalReview review() {
		var command = new WmsInventoryAdjustmentApprovalReview();
		command.setApprovalId(10L);
		command.setDecision("APPROVE");
		command.setComment("核对盘点记录和实物");
		return command;
	}

	private WmsInventoryAdjustmentCommand adjustment() {
		var command = new WmsInventoryAdjustmentCommand();
		command.setPlanDetailId(2L);
		command.setExpectedCurrentNumber(10d);
		command.setExpectedInventoryNum(7d);
		command.setDetail(selection());
		return command;
	}

	private WmsInBillDetailEntity selection() {
		var selected = new WmsInBillDetailEntity();
		selected.setMatBatch("B1");
		selected.setSupplierCode("S1");
		selected.setSupplierName("Supplier");
		selected.setMatProduceDate(LocalDate.of(2026, 10, 1));
		return selected;
	}

	private WmsInsideInventoryPlanDetailEntity detail() {
		var row = new WmsInsideInventoryPlanDetailEntity();
		row.setId(2L);
		row.setPlanId(1L);
		row.setWarehouseCode("W1");
		row.setMatCode("M1");
		row.setMatUnit("EA");
		row.setSiteCode("L1");
		row.setMatBatch("B1");
		row.setCurrentNumber(10d);
		row.setInventoryNum(7d);
		row.setStatus("1");
		row.setPlStatus("0");
		return row;
	}

	private WmsInsideInventoryPlanEntity plan() {
		var plan = new WmsInsideInventoryPlanEntity();
		plan.setId(1L);
		plan.setWarehouseCode("W1");
		plan.setStatus("1");
		plan.setHandleState("0");
		return plan;
	}

	private void actor(String username) {
		MomUser user = mock(MomUser.class);
		when(user.getUsername()).thenReturn(username);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(user, null, List.of()));
	}
}
