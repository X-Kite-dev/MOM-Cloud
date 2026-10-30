package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.dto.WmsAllocationCancelDTO;
import com.kitex.mom.wms.dto.WmsAllocationReturnDTO;
import com.kitex.mom.wms.entity.WmsAllocationBillEntity;
import com.kitex.mom.wms.entity.WmsAllocationReturnReceiptEntity;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.mapper.WmsAllocationReturnReceiptMapper;
import com.kitex.mom.wms.mapper.WmsInoutFlowMapper;
import com.kitex.mom.wms.service.WmsLiveStockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WmsAllocationPhysicalReturnTest {
    private final WmsAllocationBillServiceImpl service = mock(WmsAllocationBillServiceImpl.class, CALLS_REAL_METHODS);
    private final WmsInoutFlowMapper flows = mock(WmsInoutFlowMapper.class);
    private final WmsLiveStockService stocks = mock(WmsLiveStockService.class);
    private final WmsAllocationReturnReceiptMapper returnReceipts = mock(WmsAllocationReturnReceiptMapper.class);

    @BeforeEach
    void setUp() {
        FactoryContextHolder.setFactoryCode("F1");
        TenantContextHolder.setTenantId(1L);
        MomUser user = mock(MomUser.class);
        when(user.getUsername()).thenReturn("warehouse-operator");
        when(user.getName()).thenReturn("Warehouse operator");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.hasPermission(anyString(), anyString())).thenReturn(true);
        RemoteWarehouseService warehouses = mock(RemoteWarehouseService.class);
        MdmWarehouseEntity warehouse = new MdmWarehouseEntity()
                .setWarehouseCode("W1").setWarehouseName("Source")
                .setFactoryCode("F1").setStatus(true);
        when(warehouses.getByCode("W1")).thenReturn(R.ok(warehouse));
        RemoteWarehouseLocationService locations = mock(RemoteWarehouseLocationService.class);
        MdmWarehouseLocationEntity site = new MdmWarehouseLocationEntity();
        site.setWarehouseCode("W1");
        site.setLocationCode("S1");
        site.setLocationName("Site 1");
        site.setFactoryCode("F1");
        site.setStatus(true);
        when(locations.getByCode("S1")).thenReturn(R.ok(site));
        ReflectionTestUtils.setField(service, "permissionService", permissions);
        ReflectionTestUtils.setField(service, "remoteWarehouseService", warehouses);
        ReflectionTestUtils.setField(service, "remoteWarehouseLocationService", locations);
        ReflectionTestUtils.setField(service, "wmsInoutFlowMapper", flows);
        ReflectionTestUtils.setField(service, "wmsLiveStockService", stocks);
        ReflectionTestUtils.setField(service, "wmsAllocationReturnReceiptMapper", returnReceipts);
        doReturn(true).when(service).updateById(any(WmsAllocationBillEntity.class));
        when(flows.insert(any(WmsInoutFlowEntity.class))).thenReturn(1);
        when(returnReceipts.insert(any(WmsAllocationReturnReceiptEntity.class))).thenReturn(1);
    }

    @AfterEach
    void clear() {
        FactoryContextHolder.clear();
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void cancellationRequestDoesNotRestoreStockBeforePhysicalReturn() {
        WmsAllocationBillEntity bill = bill("IN_TRANSIT");
        doReturn(bill).when(service).getOne(any(Wrapper.class), eq(false));
        when(flows.selectList(any(Wrapper.class))).thenReturn(List.of(), List.of(issue()));

        assertTrue(service.cancel(cancelCommand()));

        assertEquals("RETURN_PENDING", bill.getAllocationState());
        assertNull(bill.getFinishTime());
        verify(stocks, never()).transferIn(any(), any(), any(), any(), any(), any());
        verify(flows, never()).insert(any(WmsInoutFlowEntity.class));
    }

    @Test
    void confirmationRestoresExactReturnedQuantityAndClosesBill() {
        WmsAllocationBillEntity bill = bill("RETURN_PENDING").setCancelRequestId("cancel-7");
        doReturn(bill).when(service).getOne(any(Wrapper.class), eq(false));
        when(flows.selectList(any(Wrapper.class))).thenReturn(List.of(), List.of(), List.of(issue()));

        assertTrue(service.confirmReturn(returnCommand(new BigDecimal("10"))));

        assertEquals("CANCELLED", bill.getAllocationState());
        assertEquals("return-7", bill.getReturnRequestId());
        verify(stocks).transferIn(any(WmsInoutFlowEntity.class), eq("W1"), eq("Source"),
                eq("S1"), eq("Site 1"), isNull());
        verify(flows).insert(argThat((WmsInoutFlowEntity flow) -> "CANCEL_RETURN".equals(flow.getTransferAction())
                && Long.valueOf(100L).equals(flow.getSourceFlowId())
                && "return-7".equals(flow.getOperationRequestId())));
    }

    @Test
    void partialPhysicalReturnCreditsOnlyConfirmedQuantityAndRemainsOpen() {
        WmsAllocationBillEntity bill = bill("RETURN_PENDING").setCancelRequestId("cancel-7");
        doReturn(bill).when(service).getOne(any(Wrapper.class), eq(false));
        when(flows.selectList(any(Wrapper.class))).thenReturn(List.of(), List.of(), List.of(issue()));

        assertTrue(service.confirmReturn(returnCommand(new BigDecimal("4"))));

        assertEquals("RETURN_PENDING", bill.getAllocationState());
        assertNull(bill.getFinishTime());
        verify(stocks).transferIn(argThat(flow -> flow.getMatNum() == 4d),
                eq("W1"), eq("Source"), eq("S1"), eq("Site 1"), isNull());
        verify(returnReceipts).insert(argThat((WmsAllocationReturnReceiptEntity receipt) -> new BigDecimal("6")
                .compareTo(receipt.getRemainingQuantity()) == 0));
    }

    @Test
    void partiallyReceivedBillReturnsOnlyTheUnreceivedBalance() {
        WmsAllocationBillEntity bill = bill("PARTIALLY_RECEIVED");
        doReturn(bill).when(service).getOne(any(Wrapper.class), eq(false));
        when(flows.selectList(any(Wrapper.class))).thenReturn(
                List.of(receipt(6d)), List.of(issue()),
                List.of(receipt(6d)), List.of(), List.of(issue()));

        assertTrue(service.cancel(cancelCommand()));
        assertEquals("RETURN_PENDING", bill.getAllocationState());
        assertTrue(service.confirmReturn(returnCommand(new BigDecimal("4"))));

        assertEquals("PARTIALLY_CANCELLED", bill.getAllocationState());
        verify(stocks).transferIn(argThat(flow -> flow.getMatNum() == 4d),
                eq("W1"), eq("Source"), eq("S1"), eq("Site 1"), isNull());
    }

    @Test
    void identicalReturnRetryDoesNotCreditStockTwice() {
        WmsAllocationBillEntity bill = bill("RETURN_PENDING").setCancelRequestId("cancel-7");
        doReturn(bill).when(service).getOne(any(Wrapper.class), eq(false));
        when(flows.selectList(any(Wrapper.class))).thenReturn(List.of(), List.of(), List.of(issue()));

        assertTrue(service.confirmReturn(returnCommand(new BigDecimal("10"))));
        var receiptCaptor = org.mockito.ArgumentCaptor.forClass(WmsAllocationReturnReceiptEntity.class);
        verify(returnReceipts).insert(receiptCaptor.capture());
        when(returnReceipts.selectOne(any(Wrapper.class))).thenReturn(receiptCaptor.getValue());
        assertTrue(service.confirmReturn(returnCommand(new BigDecimal("10"))));
        assertThrows(CheckedException.class,
                () -> service.confirmReturn(returnCommand(new BigDecimal("9"))));

        verify(stocks, times(1)).transferIn(any(), any(), any(), any(), any(), any());
        verify(flows, times(1)).insert(any(WmsInoutFlowEntity.class));
    }

    @Test
    void twoPhysicalReturnsCloseOnlyAfterAllTenUnitsAreBack() {
        WmsAllocationBillEntity bill = bill("RETURN_PENDING").setCancelRequestId("cancel-7");
        doReturn(bill).when(service).getOne(any(Wrapper.class), eq(false));
        when(flows.selectList(any(Wrapper.class))).thenReturn(
                List.of(), List.of(), List.of(issue()),
                List.of(), List.of(returned(4d)), List.of(issue()));

        assertTrue(service.confirmReturn(returnCommand(new BigDecimal("4"))));
        assertEquals("RETURN_PENDING", bill.getAllocationState());
        WmsAllocationReturnDTO second = returnCommand(new BigDecimal("6"));
        second.setRequestId("return-8");
        assertTrue(service.confirmReturn(second));

        assertEquals("CANCELLED", bill.getAllocationState());
        verify(stocks, times(2)).transferIn(any(), any(), any(), any(), any(), any());
        verify(returnReceipts, times(2)).insert(any(WmsAllocationReturnReceiptEntity.class));
    }

    @Test
    void zeroAndExcessReturnsCannotCreateAReceiptOrStock() {
        WmsAllocationBillEntity bill = bill("RETURN_PENDING").setCancelRequestId("cancel-7");
        doReturn(bill).when(service).getOne(any(Wrapper.class), eq(false));
        when(flows.selectList(any(Wrapper.class))).thenReturn(
                List.of(), List.of(), List.of(issue()),
                List.of(), List.of(), List.of(issue()));

        assertThrows(CheckedException.class,
                () -> service.confirmReturn(returnCommand(BigDecimal.ZERO)));
        assertThrows(CheckedException.class,
                () -> service.confirmReturn(returnCommand(new BigDecimal("11"))));

        verify(returnReceipts, never()).insert(any(WmsAllocationReturnReceiptEntity.class));
        verify(stocks, never()).transferIn(any(), any(), any(), any(), any(), any());
    }

    @Test
    void overReceivedFlowCannotEnterReturnPendingState() {
        WmsAllocationBillEntity bill = bill("PARTIALLY_RECEIVED");
        doReturn(bill).when(service).getOne(any(Wrapper.class), eq(false));
        when(flows.selectList(any(Wrapper.class))).thenReturn(List.of(receipt(11d)), List.of(issue()));

        assertThrows(CheckedException.class, () -> service.cancel(cancelCommand()));

        assertEquals("PARTIALLY_RECEIVED", bill.getAllocationState());
        verify(service, never()).updateById(any(WmsAllocationBillEntity.class));
        verify(stocks, never()).transferIn(any(), any(), any(), any(), any(), any());
    }

    private WmsAllocationBillEntity bill(String state) {
        WmsAllocationBillEntity bill = new WmsAllocationBillEntity().setBillNo("TR-7")
                .setOldWarehouseCode("W1").setOldWarehouseName("Source")
                .setNewWarehouseCode("W2").setFactoryCode("F1")
                .setSourceDeducted(true).setAllocationState(state);
        bill.setId(7L);
        return bill;
    }

    private WmsInoutFlowEntity issue() {
        WmsInoutFlowEntity flow = new WmsInoutFlowEntity();
        flow.setId(100L);
        flow.setBillId(7L);
        flow.setMatCode("M1");
        flow.setMatNum(10d);
        flow.setWarehouseCode("W1");
        flow.setSiteCode("S1");
        flow.setSiteName("Site 1");
        return flow;
    }

    private WmsInoutFlowEntity receipt(double quantity) {
        WmsInoutFlowEntity flow = new WmsInoutFlowEntity();
        flow.setSourceFlowId(100L);
        flow.setMatNum(quantity);
        return flow;
    }

    private WmsInoutFlowEntity returned(double quantity) {
        return receipt(quantity);
    }

    private WmsAllocationCancelDTO cancelCommand() {
        WmsAllocationCancelDTO command = new WmsAllocationCancelDTO();
        command.setId(7L);
        command.setRequestId("cancel-7");
        command.setReason("业务取消，实物待退回");
        return command;
    }

    private WmsAllocationReturnDTO returnCommand(BigDecimal quantity) {
        WmsAllocationReturnDTO command = new WmsAllocationReturnDTO();
        command.setId(7L);
        command.setRequestId("return-7");
        command.setEvidence("原库位实物复核记录");
        WmsAllocationReturnDTO.Line line = new WmsAllocationReturnDTO.Line();
        line.setSourceFlowId(100L);
        line.setReturnedQuantity(quantity);
        command.setLines(List.of(line));
        return command;
    }
}
