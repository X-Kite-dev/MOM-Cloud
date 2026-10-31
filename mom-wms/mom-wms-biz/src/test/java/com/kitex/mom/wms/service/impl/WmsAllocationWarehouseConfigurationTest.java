package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.dto.WmsAllocationBillAddDTO;
import com.kitex.mom.wms.dto.WmsDetailDTO;
import com.kitex.mom.wms.dto.app.WmsAllocationBillAppDTO;
import com.kitex.mom.wms.entity.WmsAllocationBillEntity;
import com.kitex.mom.wms.mapper.WmsAllocationBillMapper;
import com.kitex.mom.wms.mapper.WmsAllocationMobileCancelReceiptMapper;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WmsAllocationWarehouseConfigurationTest {
    private final WmsAllocationBillServiceImpl service = mock(WmsAllocationBillServiceImpl.class, CALLS_REAL_METHODS);
    private final RemoteWarehouseService warehouses = mock(RemoteWarehouseService.class);
    private final WmsWarehouseBusinessGuard guard = mock(WmsWarehouseBusinessGuard.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final WmsAllocationBillMapper bills = mock(WmsAllocationBillMapper.class);

    @BeforeEach
    void setUp() {
        FactoryContextHolder.setFactoryCode("F1");
        TenantContextHolder.setTenantId(1L);
        var actor = mock(MomUser.class);
        when(actor.getUsername()).thenReturn("operator");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
        ReflectionTestUtils.setField(service, "remoteWarehouseService", warehouses);
        ReflectionTestUtils.setField(service, "warehouseBusinessGuard", guard);
        ReflectionTestUtils.setField(service, "permissionService", permissions);
        ReflectionTestUtils.setField(service, "baseMapper", bills);
        ReflectionTestUtils.setField(service, "mobileCancelReceipts", mock(WmsAllocationMobileCancelReceiptMapper.class));
        when(warehouses.getByCode("W1")).thenReturn(R.ok(warehouse("W1")));
        when(warehouses.getByCode("W2")).thenReturn(R.ok(warehouse("W2")));
        when(permissions.hasPermission(anyString(), anyString())).thenReturn(true);
        when(guard.check(anyString(), eq("allocation"), anyBoolean())).thenReturn(true);
        when(bills.lockAgentTransfer(anyString())).thenReturn(1);
        doReturn(null).when(service).getOne(any(Wrapper.class), eq(false));
    }

    @AfterEach
    void clearScope() {
        FactoryContextHolder.clear();
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void sourceWarehouseMustEnableTransferIssueBeforeWebTransferStarts() {
        doThrow(new CheckedException("源仓未配置调拨发出")).when(guard).check("W1", "allocation", false);

        var error = assertThrows(CheckedException.class, () -> service.saveAllocationBill(webCommand()));

        assertEquals("源仓未配置调拨发出", error.getMessage());
        verify(guard, never()).check("W2", "allocation", true);
    }

    @Test
    void targetWarehouseMustEnableTransferReceiptBeforeWebTransferStarts() {
        doThrow(new CheckedException("目标仓未配置调拨接收")).when(guard).check("W2", "allocation", true);

        var error = assertThrows(CheckedException.class, () -> service.saveAllocationBill(webCommand()));

        assertEquals("目标仓未配置调拨接收", error.getMessage());
    }

    @Test
    void instantMobileTransferChecksBothWarehouseOperations() {
        doThrow(new CheckedException("目标仓未配置调拨接收")).when(guard).check("W2", "allocation", true);
        var command = new WmsAllocationBillAppDTO();
        command.setRequestId("request-mobile");
        command.setBillNo("TR-MOBILE");
        command.setOldSiteCode("S1");
        command.setNewSiteCode("S2");
        command.setMatCode("M1");
        command.setOldWarehouseCode("W1");
        command.setNewWarehouseCode("W2");
        command.setOperateNum(1d);

        var error = assertThrows(CheckedException.class, () -> service.appSaveAllocationBill(command));

        assertEquals("目标仓未配置调拨接收", error.getMessage());
        verify(guard).check("W1", "allocation", false);
    }

    @Test
    void transferReceiptRechecksTargetConfigurationBeforeStockMoves() {
        var bill = new WmsAllocationBillEntity().setOldWarehouseCode("W1")
                .setNewWarehouseCode("W2").setFactoryCode("F1").setAllocationState("IN_TRANSIT");
        bill.setId(7L);
        doReturn(bill).when(service).getOne(any(Wrapper.class), eq(false));
        doThrow(new CheckedException("目标仓未配置调拨接收")).when(guard).check("W2", "allocation", true);
        var command = webCommand();
        command.setId(7L);
        command.setRequestId("receive-7");

        var error = assertThrows(CheckedException.class, () -> service.updateAllocationBill(command));

        assertEquals("目标仓未配置调拨接收", error.getMessage());
    }

    @Test
    void identicalIssueRetryReturnsExistingResultAfterMenuChanges() {
        var command = webCommand();
        command.setFactoryCode("F1");
        String snapshot = ReflectionTestUtils.invokeMethod(service, "issueRequestSnapshot", command);
        var existing = new WmsAllocationBillEntity().setRequestSnapshot(snapshot);
        doReturn(existing).when(service).getOne(any(Wrapper.class), eq(false));

        assertEquals(Boolean.TRUE, service.saveAllocationBill(command));
        verify(guard, never()).check(anyString(), anyString(), anyBoolean());
    }

    @Test
    void identicalReceiptRetryReturnsExistingResultAfterMenuChanges() {
        var command = webCommand();
        command.setId(7L);
        command.setRequestId("receive-7");
        String snapshot = ReflectionTestUtils.invokeMethod(service, "receiveRequestSnapshot", command);
        var bill = new WmsAllocationBillEntity().setNewWarehouseCode("W2")
                .setFactoryCode("F1")
                .setReceiveRequestId("receive-7").setReceiveRequestSnapshot(snapshot)
                .setAllocationState("RECEIVED");
        bill.setId(7L);
        doReturn(bill).when(service).getOne(any(Wrapper.class), eq(false));

        assertEquals(Boolean.TRUE, service.updateAllocationBill(command));
        verify(guard, never()).check(anyString(), anyString(), anyBoolean());
    }

    private WmsAllocationBillAddDTO webCommand() {
        var command = new WmsAllocationBillAddDTO();
        command.setRequestId("request-7");
        command.setBillNo("TR-7");
        command.setOldWarehouseCode("W1");
        command.setNewWarehouseCode("W2");
        command.setDetails(List.of(new WmsDetailDTO()));
        return command;
    }

    private MdmWarehouseEntity warehouse(String code) {
        var warehouse = new MdmWarehouseEntity();
        warehouse.setWarehouseCode(code);
        warehouse.setWarehouseName(code);
        warehouse.setFactoryCode("F1");
        warehouse.setTenantId(1L);
        warehouse.setStatus(true);
        return warehouse;
    }
}
