package com.kitex.mom.wms.service.impl;

import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.wms.dto.WmsTransferReceiptDTO;
import com.kitex.mom.wms.dto.WmsTransferVarianceDTO;
import com.kitex.mom.wms.entity.WmsAllocationBillEntity;
import com.kitex.mom.wms.entity.WmsTransferReceiptEntity;
import com.kitex.mom.wms.entity.WmsTransferVarianceCaseEntity;
import com.kitex.mom.wms.entity.WmsTransferVarianceOperationEntity;
import com.kitex.mom.wms.mapper.WmsAllocationBillMapper;
import com.kitex.mom.wms.mapper.WmsTransferReceiptMapper;
import com.kitex.mom.wms.mapper.WmsTransferVarianceCaseMapper;
import com.kitex.mom.wms.mapper.WmsTransferVarianceOperationMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WmsTransferIdempotencyScopeTest {
    private final WmsTransferVarianceServiceImpl service = mock(WmsTransferVarianceServiceImpl.class, CALLS_REAL_METHODS);
    private final WmsAllocationBillMapper bills = mock(WmsAllocationBillMapper.class);
    private final WmsTransferReceiptMapper receipts = mock(WmsTransferReceiptMapper.class);
    private final WmsTransferVarianceCaseMapper cases = mock(WmsTransferVarianceCaseMapper.class);
    private final WmsTransferVarianceOperationMapper operations = mock(WmsTransferVarianceOperationMapper.class);
    private final PermissionService permissions = mock(PermissionService.class);

    WmsTransferIdempotencyScopeTest() {
        ReflectionTestUtils.setField(service, "billMapper", bills);
        ReflectionTestUtils.setField(service, "receiptMapper", receipts);
        ReflectionTestUtils.setField(service, "caseMapper", cases);
        ReflectionTestUtils.setField(service, "operationMapper", operations);
        ReflectionTestUtils.setField(service, "permissionService", permissions);
    }

    @BeforeEach
    void setUser() {
        TenantContextHolder.setTenantId(1L);
        MomUser user = mock(MomUser.class);
        when(user.getUsername()).thenReturn("receiver-a");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void clearScope() {
        FactoryContextHolder.clear();
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void receiptRetryCannotReadResultAfterWarehousePermissionWasRevoked() {
        FactoryContextHolder.setFactoryCode("F1");
        WmsTransferReceiptDTO.Command command = receiptCommand();
        WmsAllocationBillEntity bill = bill();
        when(bills.selectOne(any())).thenReturn(bill);
        when(bills.selectById(7L)).thenReturn(bill);
        when(receipts.selectOne(any())).thenReturn(existingReceipt(command));

        CheckedException error = assertThrows(CheckedException.class, () -> service.receive(command));

        assertEquals("无权接收至该仓库", error.getMessage());
    }

    @Test
    void receiptRetryCannotReadResultAcrossFactories() {
        FactoryContextHolder.setFactoryCode("F2");
        WmsTransferReceiptDTO.Command command = receiptCommand();
        WmsAllocationBillEntity bill = bill();
        when(bills.selectOne(any())).thenReturn(bill);
        when(bills.selectById(7L)).thenReturn(bill);
        when(receipts.selectOne(any())).thenReturn(existingReceipt(command));

        CheckedException error = assertThrows(CheckedException.class, () -> service.receive(command));

        assertEquals("业务数据超出当前工厂范围", error.getMessage());
    }

    @Test
    void varianceRetryCannotAcknowledgeAnotherFactoryCase() {
        FactoryContextHolder.setFactoryCode("F2");
        WmsTransferVarianceDTO.ResubmitCommand command = new WmsTransferVarianceDTO.ResubmitCommand();
        command.setCaseId(11L);
        command.setRequestId("resubmit-11");
        WmsTransferVarianceOperationEntity operation = new WmsTransferVarianceOperationEntity()
                .setCaseId(11L).setRequestId(command.getRequestId())
                .setRequestSnapshot(JSONUtil.toJsonStr(command)).setFactoryCode("F1");
        when(operations.selectOne(any())).thenReturn(operation);
        when(cases.selectOne(any())).thenReturn(new WmsTransferVarianceCaseEntity().setFactoryCode("F1"));

        CheckedException error = assertThrows(CheckedException.class, () -> service.resubmit(command));

        assertEquals("业务数据超出当前工厂范围", error.getMessage());
    }

    @Test
    void receiptRetryCannotBeClaimedByAnotherOperator() {
        FactoryContextHolder.setFactoryCode("F1");
        WmsTransferReceiptDTO.Command command = receiptCommand();
        WmsAllocationBillEntity bill = bill();
        when(bills.selectOne(any())).thenReturn(bill);
        when(receipts.selectOne(any())).thenReturn(existingReceipt(command).setReceiverCode("receiver-b"));
        when(permissions.hasPermission(anyString(), anyString())).thenReturn(true);

        CheckedException error = assertThrows(CheckedException.class, () -> service.receive(command));

        assertEquals("接收幂等号不属于当前操作人", error.getMessage());
    }

    @Test
    void varianceRetryCannotBeClaimedByAnotherOperator() {
        FactoryContextHolder.setFactoryCode("F1");
        WmsTransferVarianceDTO.ResubmitCommand command = new WmsTransferVarianceDTO.ResubmitCommand();
        command.setCaseId(11L);
        command.setRequestId("resubmit-11");
        WmsTransferVarianceOperationEntity operation = new WmsTransferVarianceOperationEntity()
                .setCaseId(11L).setRequestId(command.getRequestId())
                .setRequestSnapshot(JSONUtil.toJsonStr(command)).setFactoryCode("F1")
                .setOperatorCode("receiver-b");
        when(operations.selectOne(any())).thenReturn(operation);
        WmsTransferVarianceCaseEntity varianceCase = new WmsTransferVarianceCaseEntity().setFactoryCode("F1");
        varianceCase.setId(11L);
        when(cases.selectOne(any())).thenReturn(varianceCase);

        CheckedException error = assertThrows(CheckedException.class, () -> service.resubmit(command));

        assertEquals("差异处理幂等号不属于当前操作人", error.getMessage());
    }

    @Test
    void sameOperatorCanRetryReceiptWithNormalizedRequestId() {
        FactoryContextHolder.setFactoryCode("F1");
        WmsTransferReceiptDTO.Command command = receiptCommand();
        command.setRequestId(" receipt-7 ");
        WmsAllocationBillEntity bill = bill();
        when(bills.selectOne(any())).thenReturn(bill);
        when(bills.selectById(7L)).thenReturn(bill);
        when(permissions.hasPermission(anyString(), anyString())).thenReturn(true);
        when(receipts.selectOne(any())).thenReturn(
                existingReceipt(command).setRequestId("receipt-7").setReceiverCode("receiver-a"));

        WmsTransferReceiptDTO.Result result = service.receive(command);

        assertEquals(19L, result.getReceiptId());
        assertEquals("RECEIVED", result.getAllocationState());
    }

    @Test
    void sameOperatorCanRetryVarianceOperationAfterStateMovedOn() {
        FactoryContextHolder.setFactoryCode("F1");
        WmsTransferVarianceDTO.ResubmitCommand command = new WmsTransferVarianceDTO.ResubmitCommand();
        command.setCaseId(11L);
        command.setRequestId("resubmit-11");
        WmsTransferVarianceOperationEntity operation = new WmsTransferVarianceOperationEntity()
                .setCaseId(11L).setRequestId(command.getRequestId())
                .setRequestSnapshot(JSONUtil.toJsonStr(command)).setFactoryCode("F1")
                .setOperatorCode("receiver-a");
        when(operations.selectOne(any())).thenReturn(operation);
        WmsTransferVarianceCaseEntity varianceCase = new WmsTransferVarianceCaseEntity()
                .setFactoryCode("F1").setStatus("PENDING_PLAN_REVIEW");
        varianceCase.setId(11L);
        when(cases.selectOne(any())).thenReturn(varianceCase);

        service.resubmit(command);
    }

    private WmsTransferReceiptDTO.Command receiptCommand() {
        WmsTransferReceiptDTO.Command command = new WmsTransferReceiptDTO.Command();
        command.setAllocationBillId(7L);
        command.setRequestId("receipt-7");
        command.setFinalReceipt(true);
        command.setLines(List.of());
        return command;
    }

    private WmsAllocationBillEntity bill() {
        WmsAllocationBillEntity bill = new WmsAllocationBillEntity().setFactoryCode("F1")
                .setNewWarehouseCode("W2").setAllocationState("RECEIVED");
        bill.setId(7L);
        bill.setTenantId(1L);
        return bill;
    }

    private WmsTransferReceiptEntity existingReceipt(WmsTransferReceiptDTO.Command command) {
        String snapshot = ReflectionTestUtils.invokeMethod(service, "receiptSnapshot", command);
        WmsTransferReceiptEntity receipt = new WmsTransferReceiptEntity()
                .setAllocationBillId(7L).setRequestId(command.getRequestId())
                .setRequestSnapshot(snapshot).setFactoryCode("F1");
        receipt.setId(19L);
        return receipt;
    }
}
