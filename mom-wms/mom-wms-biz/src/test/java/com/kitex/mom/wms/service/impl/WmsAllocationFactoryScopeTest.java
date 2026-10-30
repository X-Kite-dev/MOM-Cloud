package com.kitex.mom.wms.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.wms.dto.WmsAllocationBillAddDTO;
import com.kitex.mom.wms.dto.WmsAllocationCancelDTO;
import com.kitex.mom.wms.dto.WmsDetailDTO;
import com.kitex.mom.wms.entity.WmsAllocationBillEntity;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.mapper.WmsInoutFlowMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WmsAllocationFactoryScopeTest {
    private final WmsAllocationBillServiceImpl service = mock(WmsAllocationBillServiceImpl.class, CALLS_REAL_METHODS);
    private final PermissionService permissions = mock(PermissionService.class);

    WmsAllocationFactoryScopeTest() {
        FactoryContextHolder.setFactoryCode("F2");
        TenantContextHolder.setTenantId(1L);
        ReflectionTestUtils.setField(service, "permissionService", permissions);
        when(permissions.hasPermission(anyString(), anyString())).thenReturn(true);
    }

    @AfterEach
    void clearScope() {
        FactoryContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    void legacyReceiptRejectsBillFromAnotherFactoryBeforeReplay() {
        WmsAllocationBillAddDTO command = new WmsAllocationBillAddDTO();
        command.setId(7L);
        command.setRequestId("receive-7");
        command.setDetails(List.of(new WmsDetailDTO()));
        String snapshot = ReflectionTestUtils.invokeMethod(service, "receiveRequestSnapshot", command);
        WmsAllocationBillEntity bill = bill().setAllocationState("RECEIVED")
                .setReceiveRequestId("receive-7").setReceiveRequestSnapshot(snapshot);
        doReturn(bill).when(service).getOne(any(Wrapper.class), eq(false));

        CheckedException error = assertThrows(CheckedException.class, () -> service.updateAllocationBill(command));

        assertEquals("业务数据超出当前工厂范围", error.getMessage());
    }

    @Test
    void cancellationRejectsBillFromAnotherFactoryBeforeReplay() {
        WmsAllocationCancelDTO command = new WmsAllocationCancelDTO();
        command.setId(7L);
        command.setRequestId("cancel-7");
        command.setReason("取消在途");
        WmsAllocationBillEntity bill = bill().setAllocationState("CANCELLED")
                .setCancelRequestId("cancel-7")
                .setCancelRequestSnapshot(JSONUtil.toJsonStr(Map.of("reason", "取消在途")));
        doReturn(bill).when(service).getOne(any(Wrapper.class), eq(false));

        CheckedException error = assertThrows(CheckedException.class, () -> service.cancel(command));

        assertEquals("业务数据超出当前工厂范围", error.getMessage());
    }

    @Test
    void detailsRejectBillFromAnotherFactoryBeforeReadingFlows() {
        WmsAllocationBillEntity bill = bill();
        doReturn(bill).when(service).getById(7L);
        WmsInoutFlowMapper flows = mock(WmsInoutFlowMapper.class);
        ReflectionTestUtils.setField(service, "wmsInoutFlowMapper", flows);
        when(flows.selectList(any(Wrapper.class))).thenReturn(List.<WmsInoutFlowEntity>of());

        CheckedException error = assertThrows(CheckedException.class, () -> service.getDetails(7L));

        assertEquals("业务数据超出当前工厂范围", error.getMessage());
    }

    private WmsAllocationBillEntity bill() {
        WmsAllocationBillEntity bill = new WmsAllocationBillEntity()
                .setFactoryCode("F1").setOldWarehouseCode("W1").setNewWarehouseCode("W2");
        bill.setId(7L);
        return bill;
    }
}
