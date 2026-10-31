package com.kitex.mom.wms.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.wms.dto.WmsFinishedGoodsReceiptCorrectionCommand;
import com.kitex.mom.wms.entity.WmsFinishedGoodsReceiptCorrectionEntity;
import com.kitex.mom.wms.entity.WmsFinishedGoodsReceiptCorrectionOperationEntity;
import com.kitex.mom.wms.entity.WmsFinishedGoodsReceiptCorrectionDetailEntity;
import com.kitex.mom.wms.mapper.WmsFinishedGoodsReceiptCorrectionDetailMapper;
import com.kitex.mom.wms.mapper.WmsFinishedGoodsReceiptCorrectionOperationMapper;
import com.kitex.mom.wms.mapper.WmsInNoticeMapper;
import com.kitex.mom.wms.service.impl.WmsFinishedGoodsReceiptCorrectionServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WmsFinishedGoodsCorrectionDecisionReplayTest {

	@AfterEach
	void cleanup() {
		TenantContextHolder.clear();
		FactoryContextHolder.clear();
		SecurityContextHolder.clearContext();
	}

	@Test
	void anotherActorCannotClaimCompletedWithdrawal() throws Exception {
		var command = command();
		var service = service("WITHDRAW", command.getRequestId(), hash(JSONUtil.toJsonStr(command)));

		assertThrows(CheckedException.class, () -> service.withdraw(300L, command));
	}

	@Test
	void anotherActorCannotClaimCompletedRejection() throws Exception {
		var command = command();
		var service = service("REJECT", command.getRequestId(), hash(JSONUtil.toJsonStr(command)));

		assertThrows(CheckedException.class, () -> service.reject(300L, command));
	}

	@Test
	void anotherActorCannotClaimCompletedPhysicalApproval() throws Exception {
		var service = service("APPROVE_EXECUTE", "APPROVE:300", hash("APPROVE|300"));

		assertThrows(CheckedException.class, () -> service.approve(300L));
	}

	@Test
	void originalActorWithoutFrozenWarehousePermissionCannotReplay() throws Exception {
		var command = command();
		var service = service("WITHDRAW", command.getRequestId(), hash(JSONUtil.toJsonStr(command)));
		var user = mock(MomUser.class);
		when(user.getUsername()).thenReturn("original-operator");
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null));
		reset((PermissionService) ReflectionTestUtils.getField(service, "decisionPermissions"));

		assertThrows(CheckedException.class, () -> service.withdraw(300L, command));
	}

	@Test
	void originalAuthorizedReviewerCanReplayWithoutExecutingPhysicalStockAgain() throws Exception {
		var service = service("APPROVE_EXECUTE", "APPROVE:300", hash("APPROVE|300"));
		var user = mock(MomUser.class);
		when(user.getUsername()).thenReturn("original-operator");
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null));

		assertEquals("EXECUTED", service.approve(300L).getStatus());
	}

	@Test
	void missingWarehouseDetailCannotReduceTheOriginalPermissionScope() throws Exception {
		var command = command();
		var service = service("WITHDRAW", command.getRequestId(), hash(JSONUtil.toJsonStr(command)));
		var row = service.getOne(new LambdaQueryWrapper<WmsFinishedGoodsReceiptCorrectionEntity>(), false);
		row.setStockLineCount(2);
		asOriginalActor();

		assertThrows(CheckedException.class, () -> service.withdraw(300L, command));
	}

	@SuppressWarnings("unchecked")
	@Test
	void completeAuthorizedLegacyApplicationAboveOneThousandLinesCanStillReplay() throws Exception {
		var service = service("APPROVE_EXECUTE", "APPROVE:300", hash("APPROVE|300"));
		var row = service.getOne(new LambdaQueryWrapper<WmsFinishedGoodsReceiptCorrectionEntity>(), false);
		row.setStockLineCount(1001);
		var details = (WmsFinishedGoodsReceiptCorrectionDetailMapper) ReflectionTestUtils.getField(service, "detailMapper");
		var line = details.selectList(new LambdaQueryWrapper<WmsFinishedGoodsReceiptCorrectionDetailEntity>()).get(0);
		when(details.selectList(any(Wrapper.class))).thenReturn(Collections.nCopies(1001, line));
		asOriginalActor();

		assertEquals("EXECUTED", service.approve(300L).getStatus());
	}

	private void asOriginalActor() {
		var user = mock(MomUser.class);
		when(user.getUsername()).thenReturn("original-operator");
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null));
	}

	@SuppressWarnings("unchecked")
	private WmsFinishedGoodsReceiptCorrectionServiceImpl service(String action, String request, String fingerprint) {
		TenantContextHolder.setTenantId(1L);
		FactoryContextHolder.setFactoryCode("F1");
		var user = mock(MomUser.class);
		when(user.getUsername()).thenReturn("another-operator");
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null));
		var row = new WmsFinishedGoodsReceiptCorrectionEntity();
		row.setId(300L);
		row.setTenantId(1L);
		row.setFactoryCode("F1").setNoticeId(100L).setStatus("EXECUTED").setStockLineCount(1);
		var operation = new WmsFinishedGoodsReceiptCorrectionOperationEntity();
		operation.setTenantId(1L);
		operation.setFactoryCode("F1").setCorrectionId(300L).setCommandId(request)
				.setCommandFingerprint(fingerprint).setOperationType(action).setOperatorCode("original-operator");
		var operations = mock(WmsFinishedGoodsReceiptCorrectionOperationMapper.class);
		when(operations.selectOne(any(Wrapper.class), eq(false))).thenReturn(operation);
		var service = mock(WmsFinishedGoodsReceiptCorrectionServiceImpl.class, CALLS_REAL_METHODS);
		doReturn(row).when(service).getOne(any(Wrapper.class), eq(false));
		ReflectionTestUtils.setField(service, "operationMapper", operations);
		ReflectionTestUtils.setField(service, "noticeMapper", mock(WmsInNoticeMapper.class));
		var details = mock(WmsFinishedGoodsReceiptCorrectionDetailMapper.class);
		var line = new WmsFinishedGoodsReceiptCorrectionDetailEntity();
		line.setTenantId(1L);
		line.setDelFlag("0");
		line.setFactoryCode("F1").setWarehouseCode("W1");
		when(details.selectList(any(Wrapper.class))).thenReturn(List.of(line));
		ReflectionTestUtils.setField(service, "detailMapper", details);
		var permissions = mock(PermissionService.class);
		when(permissions.hasPermission("wms_bill_add_in_product_W1")).thenReturn(true);
		when(permissions.hasPermission("wms_bill_review_in_product_W1")).thenReturn(true);
		ReflectionTestUtils.setField(service, "decisionPermissions", permissions);
		return service;
	}

	private WmsFinishedGoodsReceiptCorrectionCommand command() {
		var command = new WmsFinishedGoodsReceiptCorrectionCommand();
		command.setRequestId("FGR-DECISION-ORIGINAL-0001");
		command.setReason("核对撤回");
		command.setRejectReason("核对驳回");
		return command;
	}

	private String hash(String value) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
	}
}
