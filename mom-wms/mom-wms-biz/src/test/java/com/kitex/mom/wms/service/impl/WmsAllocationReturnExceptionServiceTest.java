package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.wms.dto.WmsAllocationReturnExceptionDTO;
import com.kitex.mom.wms.entity.WmsAllocationBillEntity;
import com.kitex.mom.wms.entity.WmsAllocationReturnExceptionEntity;
import com.kitex.mom.wms.entity.WmsAllocationReturnExceptionLineEntity;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.mapper.WmsAllocationBillMapper;
import com.kitex.mom.wms.mapper.WmsAllocationReturnExceptionLineMapper;
import com.kitex.mom.wms.mapper.WmsAllocationReturnExceptionMapper;
import com.kitex.mom.wms.mapper.WmsInoutFlowMapper;
import com.kitex.mom.wms.service.WmsLiveStockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WmsAllocationReturnExceptionServiceTest {
    private final WmsAllocationBillMapper bills = mock(WmsAllocationBillMapper.class);
    private final WmsAllocationReturnExceptionMapper cases = mock(WmsAllocationReturnExceptionMapper.class);
    private final WmsAllocationReturnExceptionLineMapper lines = mock(WmsAllocationReturnExceptionLineMapper.class);
    private final WmsInoutFlowMapper flows = mock(WmsInoutFlowMapper.class);
    private final WmsLiveStockService stock = mock(WmsLiveStockService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final WmsAllocationReturnExceptionServiceImpl service =
            new WmsAllocationReturnExceptionServiceImpl(bills, cases, lines, flows, stock, permissions);
    private final WmsAllocationBillEntity bill = bill();

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        user("reporter");
        when(permissions.hasPermission(anyString(), anyString())).thenReturn(true);
        when(permissions.hasPermission(anyString())).thenReturn(true);
        when(bills.selectOne(any(Wrapper.class))).thenReturn(bill);
        when(bills.updateById(any(WmsAllocationBillEntity.class))).thenReturn(1);
        when(cases.insert(any(WmsAllocationReturnExceptionEntity.class))).thenAnswer(invocation -> {
            ((WmsAllocationReturnExceptionEntity) invocation.getArgument(0)).setId(88L);
            return 1;
        });
        when(cases.updateById(any(WmsAllocationReturnExceptionEntity.class))).thenReturn(1);
        when(lines.insert(any(WmsAllocationReturnExceptionLineEntity.class))).thenReturn(1);
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void reportFreezesOnlyTheUnreturnedBalanceWithoutCreditingStock() {
        when(flows.selectList(any(Wrapper.class))).thenReturn(
                List.of(issue()), List.of(flow(2d)), List.of(flow(2d)));

        WmsAllocationReturnExceptionEntity result = service.report(report());

        assertEquals("RETURN_EXCEPTION_PENDING", bill.getAllocationState());
        assertEquals(88L, bill.getReturnExceptionCaseId());
        assertEquals("PENDING_REVIEW", result.getStatus());
        verify(lines).insert(argThat((WmsAllocationReturnExceptionLineEntity line) ->
                line.getSourceFlowId() == 100L &&
                        line.getExceptionQuantity().compareTo(new BigDecimal("6")) == 0));
    }

    @Test
    void independentApprovalClosesCustodyWithoutAddingStock() {
        WmsAllocationReturnExceptionEntity exception = pendingCase();
        when(cases.selectById(88L)).thenReturn(exception);
        when(cases.selectOne(any(Wrapper.class))).thenReturn(exception);
        when(lines.selectList(any(Wrapper.class))).thenReturn(List.of(frozen(6d)));
        when(flows.selectList(any(Wrapper.class))).thenReturn(
                List.of(issue()), List.of(flow(2d)), List.of(flow(2d)), List.of(issue()));
        user("reviewer");

        WmsAllocationReturnExceptionEntity result = service.review(review("APPROVE"));

        assertEquals("CUSTODY_CLOSED", result.getStatus());
        assertEquals("RETURN_EXCEPTION_CLOSED", bill.getAllocationState());
        assertEquals("LOSS-2026-01", result.getReferenceNo());
        assertNotNull(bill.getFinishTime());
        verify(bills).updateById(bill);
        verify(stock).releaseTransferSourceContainer(argThat(flow -> flow.getId() == 100L));
    }

    @Test
    void rejectionReopensPhysicalReturnAndReporterCannotSelfApprove() {
        WmsAllocationReturnExceptionEntity exception = pendingCase();
        when(cases.selectById(88L)).thenReturn(exception);
        when(cases.selectOne(any(Wrapper.class))).thenReturn(exception);
        assertThrows(CheckedException.class, () -> service.review(review("APPROVE")));
        verify(cases, never()).updateById(any(WmsAllocationReturnExceptionEntity.class));

        when(lines.selectList(any(Wrapper.class))).thenReturn(List.of(frozen(6d)));
        when(flows.selectList(any(Wrapper.class))).thenReturn(
                List.of(issue()), List.of(flow(2d)), List.of(flow(2d)), List.of(issue()));
        user("reviewer");
        WmsAllocationReturnExceptionEntity result = service.review(review("REJECT"));

        assertEquals("REJECTED", result.getStatus());
        assertEquals("RETURN_PENDING", bill.getAllocationState());
        assertNull(bill.getFinishTime());
        verify(stock, never()).releaseTransferSourceContainer(any(WmsInoutFlowEntity.class));
    }

    @Test
    void changedOutstandingQuantityBlocksApproval() {
        WmsAllocationReturnExceptionEntity exception = pendingCase();
        when(cases.selectById(88L)).thenReturn(exception);
        when(cases.selectOne(any(Wrapper.class))).thenReturn(exception);
        when(lines.selectList(any(Wrapper.class))).thenReturn(List.of(frozen(6d)));
        when(flows.selectList(any(Wrapper.class))).thenReturn(
                List.of(issue()), List.of(flow(3d)), List.of(flow(2d)));
        user("reviewer");

        assertThrows(CheckedException.class, () -> service.review(review("APPROVE")));

        assertEquals("RETURN_EXCEPTION_PENDING", bill.getAllocationState());
        verify(cases, never()).updateById(any(WmsAllocationReturnExceptionEntity.class));
    }

    @Test
    void offsettingReceiptAndReturnChangesStillBlockApproval() {
        WmsAllocationReturnExceptionEntity exception = pendingCase();
        when(cases.selectById(88L)).thenReturn(exception);
        when(cases.selectOne(any(Wrapper.class))).thenReturn(exception);
        when(lines.selectList(any(Wrapper.class))).thenReturn(List.of(frozen(6d)));
        when(flows.selectList(any(Wrapper.class))).thenReturn(
                List.of(issue()), List.of(flow(3d)), List.of(flow(1d)));
        user("reviewer");

        assertThrows(CheckedException.class, () -> service.review(review("APPROVE")));

        verify(cases, never()).updateById(any(WmsAllocationReturnExceptionEntity.class));
    }

    @Test
    void staleSnapshotCanBeRejectedToReopenPhysicalReturn() {
        WmsAllocationReturnExceptionEntity exception = pendingCase();
        when(cases.selectById(88L)).thenReturn(exception);
        when(cases.selectOne(any(Wrapper.class))).thenReturn(exception);
        when(lines.selectList(any(Wrapper.class))).thenReturn(List.of(frozen(6d)));
        when(flows.selectList(any(Wrapper.class))).thenReturn(
                List.of(issue()), List.of(flow(3d)), List.of(flow(2d)));
        user("reviewer");

        WmsAllocationReturnExceptionEntity result = service.review(review("REJECT"));

        assertEquals("REJECTED", result.getStatus());
        assertEquals("RETURN_PENDING", bill.getAllocationState());
        verify(stock, never()).releaseTransferSourceContainer(any(WmsInoutFlowEntity.class));
    }

    @Test
    void exactReportReplayDoesNotCreateAnotherCase() {
        when(flows.selectList(any(Wrapper.class))).thenReturn(
                List.of(issue()), List.of(flow(2d)), List.of(flow(2d)));
        WmsAllocationReturnExceptionEntity first = service.report(report());
        when(cases.selectOne(any(Wrapper.class))).thenReturn(first);

        assertSame(first, service.report(report()));

        verify(cases, times(1)).insert(any(WmsAllocationReturnExceptionEntity.class));
        verify(lines, times(1)).insert(any(WmsAllocationReturnExceptionLineEntity.class));
    }

    @Test
    void exactApprovalReplayIsReadOnlyAndChangedDecisionIsRejected() {
        WmsAllocationReturnExceptionEntity exception = pendingCase();
        when(cases.selectById(88L)).thenReturn(exception);
        when(cases.selectOne(any(Wrapper.class))).thenReturn(exception);
        when(lines.selectList(any(Wrapper.class))).thenReturn(List.of(frozen(6d)));
        when(flows.selectList(any(Wrapper.class))).thenReturn(
                List.of(issue()), List.of(flow(2d)), List.of(flow(2d)), List.of(issue()));
        user("reviewer");

        assertSame(exception, service.review(review("APPROVE")));
        assertSame(exception, service.review(review("APPROVE")));
        assertThrows(CheckedException.class, () -> service.review(review("REJECT")));

        verify(cases, times(1)).updateById(exception);
        verify(bills, times(1)).updateById(bill);
        verify(stock, times(1)).releaseTransferSourceContainer(any(WmsInoutFlowEntity.class));
    }

    private WmsAllocationBillEntity bill() {
        WmsAllocationBillEntity value = new WmsAllocationBillEntity()
                .setFactoryCode("F1").setOldWarehouseCode("W1").setNewWarehouseCode("W2")
                .setBillNo("TR-7").setSourceDeducted(true).setAllocationState("RETURN_PENDING");
        value.setId(7L);
        return value;
    }

    private WmsAllocationReturnExceptionEntity pendingCase() {
        bill.setAllocationState("RETURN_EXCEPTION_PENDING").setReturnExceptionCaseId(88L);
        WmsAllocationReturnExceptionEntity exception = new WmsAllocationReturnExceptionEntity()
                .setAllocationBillId(7L).setFactoryCode("F1")
                .setStatus("PENDING_REVIEW").setReporterCode("reporter");
        exception.setId(88L);
        return exception;
    }

    private WmsInoutFlowEntity issue() {
        WmsInoutFlowEntity flow = flow(10d);
        flow.setId(100L);
        flow.setMatCode("M1");
        flow.setMatBatch("B1");
        return flow;
    }

    private WmsInoutFlowEntity flow(double quantity) {
        WmsInoutFlowEntity flow = new WmsInoutFlowEntity();
        flow.setSourceFlowId(100L);
        flow.setMatNum(quantity);
        return flow;
    }

    private WmsAllocationReturnExceptionLineEntity frozen(double quantity) {
        return new WmsAllocationReturnExceptionLineEntity()
                .setSourceFlowId(100L).setMaterialCode("M1").setMaterialBatch("B1")
                .setIssuedQuantity(BigDecimal.TEN).setReceivedQuantity(new BigDecimal("2"))
                .setReturnedQuantity(new BigDecimal("2"))
                .setExceptionQuantity(BigDecimal.valueOf(quantity));
    }

    private WmsAllocationReturnExceptionDTO.Report report() {
        WmsAllocationReturnExceptionDTO.Report command = new WmsAllocationReturnExceptionDTO.Report();
        command.setAllocationBillId(7L);
        command.setRequestId("report-7");
        command.setReasonCode("TRANSPORT_LOSS");
        command.setReasonDescription("在途交接后丢失");
        command.setEvidence("运输异常记录 A-7");
        return command;
    }

    private WmsAllocationReturnExceptionDTO.Review review(String decision) {
        WmsAllocationReturnExceptionDTO.Review command = new WmsAllocationReturnExceptionDTO.Review();
        command.setCaseId(88L);
        command.setRequestId("review-7");
        command.setDecision(decision);
        command.setComment("复核交接与监控记录");
        command.setDispositionCode("APPROVE".equals(decision) ? "LOSS" : null);
        command.setReferenceNo("APPROVE".equals(decision) ? "LOSS-2026-01" : null);
        return command;
    }

    private void user(String code) {
        MomUser user = mock(MomUser.class);
        when(user.getUsername()).thenReturn(code);
        when(user.getName()).thenReturn(code);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
