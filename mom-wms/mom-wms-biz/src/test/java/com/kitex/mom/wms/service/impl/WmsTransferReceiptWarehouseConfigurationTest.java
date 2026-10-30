package com.kitex.mom.wms.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.wms.dto.WmsTransferReceiptDTO;
import com.kitex.mom.wms.entity.WmsAllocationBillEntity;
import com.kitex.mom.wms.entity.WmsTransferReceiptEntity;
import com.kitex.mom.wms.mapper.WmsAllocationBillMapper;
import com.kitex.mom.wms.mapper.WmsTransferReceiptMapper;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class WmsTransferReceiptWarehouseConfigurationTest {
    @AfterEach
    void clearScope() {
        FactoryContextHolder.clear();
    }

    @Test
    void partialReceiptRechecksTargetWarehouseBusinessBeforeWritingStock() {
        FactoryContextHolder.setFactoryCode("F1");
        var service = mock(WmsTransferVarianceServiceImpl.class, CALLS_REAL_METHODS);
        var bills = mock(WmsAllocationBillMapper.class);
        var receipts = mock(WmsTransferReceiptMapper.class);
        var permissions = mock(PermissionService.class);
        var guard = mock(WmsWarehouseBusinessGuard.class);
        ReflectionTestUtils.setField(service, "billMapper", bills);
        ReflectionTestUtils.setField(service, "receiptMapper", receipts);
        ReflectionTestUtils.setField(service, "permissionService", permissions);
        ReflectionTestUtils.setField(service, "warehouseBusinessGuard", guard);
        when(permissions.hasPermission(anyString(), anyString())).thenReturn(true);
        var bill = new WmsAllocationBillEntity().setNewWarehouseCode("W2")
                .setFactoryCode("F1").setSourceDeducted(true).setAllocationState("IN_TRANSIT");
        bill.setId(7L);
        when(bills.selectOne(any())).thenReturn(bill);
        doThrow(new CheckedException("目标仓未配置调拨接收")).when(guard).check("W2", "allocation", true);
        var command = new WmsTransferReceiptDTO.Command();
        command.setAllocationBillId(7L);
        command.setRequestId("receive-partial-7");
        command.setFinalReceipt(false);
        command.setLines(List.of());

        var error = assertThrows(CheckedException.class, () -> service.receive(command));

        assertEquals("目标仓未配置调拨接收", error.getMessage());
        verify(receipts, never()).insert(any(WmsTransferReceiptEntity.class));
    }
}
