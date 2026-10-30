package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.wms.dto.WmsDetailDTO;
import com.kitex.mom.wms.entity.WmsAllocationBillEntity;
import com.kitex.mom.wms.entity.WmsAllocationReturnExceptionLineEntity;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.entity.WmsTransferVarianceLineEntity;
import com.kitex.mom.wms.mapper.WmsInoutFlowMapper;
import com.kitex.mom.wms.mapper.WmsAllocationReturnExceptionLineMapper;
import com.kitex.mom.wms.mapper.WmsTransferVarianceLineMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WmsAllocationDetailBalanceTest {
    private final WmsAllocationBillServiceImpl service = mock(WmsAllocationBillServiceImpl.class, CALLS_REAL_METHODS);
    private final WmsInoutFlowMapper flows = mock(WmsInoutFlowMapper.class);
    private final WmsTransferVarianceLineMapper varianceLines = mock(WmsTransferVarianceLineMapper.class);
    private final WmsAllocationReturnExceptionLineMapper returnExceptionLines = mock(WmsAllocationReturnExceptionLineMapper.class);
    private final PermissionService permissions = mock(PermissionService.class);

    WmsAllocationDetailBalanceTest() {
        FactoryContextHolder.setFactoryCode("F1");
        TenantContextHolder.setTenantId(1L);
        ReflectionTestUtils.setField(service, "wmsInoutFlowMapper", flows);
        ReflectionTestUtils.setField(service, "wmsTransferVarianceLineMapper", varianceLines);
        ReflectionTestUtils.setField(service, "wmsAllocationReturnExceptionLineMapper", returnExceptionLines);
        ReflectionTestUtils.setField(service, "permissionService", permissions);
        when(permissions.hasPermission(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
    }

    @AfterEach
    void clearScope() {
        FactoryContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    void closedReceiptWithMissingTerminalEvidenceShowsUnreconciledQuantity() {
        WmsDetailDTO line = detail("RECEIVED", List.of(receive(6d)));

        assertEquals(BigDecimal.ZERO, line.getInTransitQuantity());
        assertEquals(new BigDecimal("4.0"), line.getUnreconciledQuantity());
        assertEquals("RECONCILIATION_REQUIRED", line.getTransferLineState());
    }

    @Test
    void openPartialReceiptKeepsRemainingQuantityInTransit() {
        WmsDetailDTO line = detail("PARTIALLY_RECEIVED", List.of(receive(6d)));

        assertEquals(new BigDecimal("4.0"), line.getInTransitQuantity());
        assertEquals(BigDecimal.ZERO, line.getUnreconciledQuantity());
        assertEquals("PARTIAL", line.getTransferLineState());
    }

    @Test
    void partialCancellationWithMatchingReturnIsFullyBalanced() {
        WmsDetailDTO line = detail("PARTIALLY_CANCELLED", List.of(receive(6d), cancelReturn(4d)));

        assertEquals(BigDecimal.ZERO, line.getInTransitQuantity());
        assertEquals(0, line.getUnreconciledQuantity().compareTo(BigDecimal.ZERO));
        assertEquals("CANCELLED", line.getTransferLineState());
    }

    @Test
    void partialPhysicalReturnKeepsOnlyTheUnreturnedBalanceOpen() {
        WmsDetailDTO line = detail("RETURN_PENDING", List.of(cancelReturn(4d)));

        assertEquals(new BigDecimal("6.0"), line.getInTransitQuantity());
        assertEquals(BigDecimal.ZERO, line.getUnreconciledQuantity());
        assertEquals("RETURN_PENDING", line.getTransferLineState());
    }

    @Test
    void aReturnedLineIsClosedEvenIfAnotherLineKeepsTheBillPending() {
        WmsDetailDTO line = detail("RETURN_PENDING", List.of(cancelReturn(10d)));

        assertEquals(0, line.getInTransitQuantity().compareTo(BigDecimal.ZERO));
        assertEquals("CANCELLED", line.getTransferLineState());
    }

    @Test
    void pendingReturnExceptionRetainsPhysicalOutstandingQuantity() {
        WmsDetailDTO line = detail("RETURN_EXCEPTION_PENDING", List.of(receive(2d), cancelReturn(2d)));

        assertEquals(new BigDecimal("6.0"), line.getInTransitQuantity());
        assertEquals(BigDecimal.ZERO, line.getUnreconciledQuantity());
        assertEquals("RETURN_EXCEPTION_PENDING", line.getTransferLineState());
    }

    @Test
    void approvedCustodyExceptionBalancesOnlyItsFrozenQuantity() {
        when(returnExceptionLines.selectList(any(Wrapper.class))).thenReturn(List.of(
                new WmsAllocationReturnExceptionLineEntity()
                        .setSourceFlowId(100L).setExceptionQuantity(new BigDecimal("6.0"))));

        WmsDetailDTO line = detail("RETURN_EXCEPTION_CLOSED", List.of(receive(2d), cancelReturn(2d)));

        assertEquals(0, line.getUnreconciledQuantity().compareTo(BigDecimal.ZERO));
        assertEquals(new BigDecimal("6.0"), line.getReturnExceptionQuantity());
        assertEquals("RETURN_EXCEPTION_CLOSED", line.getTransferLineState());
    }

    @Test
    void closedShortReceiptIsBalancedByRecordedVariance() {
        WmsTransferVarianceLineEntity variance = new WmsTransferVarianceLineEntity()
                .setSourceFlowId(100L).setShortQuantity(new BigDecimal("4.0"))
                .setDispositionStatus("PENDING");
        WmsDetailDTO line = detail("DISCREPANCY_PENDING", List.of(receive(6d)), variance);

        assertEquals(0, line.getUnreconciledQuantity().compareTo(BigDecimal.ZERO));
        assertEquals("VARIANCE_PENDING", line.getTransferLineState());
    }

    @Test
    void excessAcceptedFlowShowsUnreconciledQuantity() {
        WmsDetailDTO line = detail("RECEIVED", List.of(receive(12d)));

        assertEquals(new BigDecimal("2.0"), line.getUnreconciledQuantity());
        assertEquals("RECONCILIATION_REQUIRED", line.getTransferLineState());
    }

    private WmsDetailDTO detail(String state, List<WmsInoutFlowEntity> terminals) {
        return detail(state, terminals, null);
    }

    private WmsDetailDTO detail(String state, List<WmsInoutFlowEntity> terminals,
                                WmsTransferVarianceLineEntity variance) {
        WmsAllocationBillEntity bill = new WmsAllocationBillEntity().setFactoryCode("F1")
                .setOldWarehouseCode("W1").setNewWarehouseCode("W2")
                .setAllocationState(state).setSourceDeducted(true);
        bill.setId(7L);
        if (variance != null) bill.setVarianceCaseId(20L);
        if ("RETURN_EXCEPTION_CLOSED".equals(state)) bill.setReturnExceptionCaseId(88L);
        doReturn(bill).when(service).getById(7L);
        when(flows.selectList(any(Wrapper.class))).thenReturn(List.of(issue()), terminals);
        if (variance != null) when(varianceLines.selectList(any(Wrapper.class))).thenReturn(List.of(variance));
        return service.getDetails(7L).getDetails().get(0);
    }

    private WmsInoutFlowEntity issue() {
        WmsInoutFlowEntity flow = new WmsInoutFlowEntity();
        flow.setId(100L);
        flow.setMatCode("M1");
        flow.setMatNum(10d);
        flow.setWarehouseCode("W1");
        return flow;
    }

    private WmsInoutFlowEntity receive(double quantity) {
        WmsInoutFlowEntity flow = new WmsInoutFlowEntity();
        flow.setSourceFlowId(100L);
        flow.setMatNum(quantity);
        flow.setTransferAction("RECEIVE");
        return flow;
    }

    private WmsInoutFlowEntity cancelReturn(double quantity) {
        WmsInoutFlowEntity flow = new WmsInoutFlowEntity();
        flow.setSourceFlowId(100L);
        flow.setMatNum(quantity);
        flow.setTransferAction("CANCEL_RETURN");
        return flow;
    }
}
