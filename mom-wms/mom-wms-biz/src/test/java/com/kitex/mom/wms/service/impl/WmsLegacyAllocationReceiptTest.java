package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.dto.WmsAllocationBillAddDTO;
import com.kitex.mom.wms.dto.WmsDetailDTO;
import com.kitex.mom.wms.entity.WmsAllocationBillEntity;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.enums.InOutEnum;
import com.kitex.mom.wms.enums.OutBillTypeEnum;
import com.kitex.mom.wms.mapper.WmsInoutFlowMapper;
import com.kitex.mom.wms.service.WmsLiveStockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WmsLegacyAllocationReceiptTest {
    private final WmsAllocationBillServiceImpl service = mock(WmsAllocationBillServiceImpl.class, CALLS_REAL_METHODS);
    private final WmsInoutFlowMapper flows = mock(WmsInoutFlowMapper.class);
    private final WmsLiveStockService stocks = mock(WmsLiveStockService.class);

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        MomUser actor = mock(MomUser.class);
        when(actor.getUsername()).thenReturn("operator");
        when(actor.getName()).thenReturn("Operator");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, null, List.of()));
        RemoteWarehouseService warehouses = mock(RemoteWarehouseService.class);
        RemoteWarehouseLocationService locations = mock(RemoteWarehouseLocationService.class);
        MdmWarehouseEntity warehouse = new MdmWarehouseEntity();
        warehouse.setWarehouseCode("W2");
        warehouse.setWarehouseName("Target");
        warehouse.setFactoryCode("F1");
        warehouse.setTenantId(1L);
        warehouse.setStatus(true);
        when(warehouses.getByCode("W2")).thenReturn(R.ok(warehouse));
        MdmWarehouseLocationEntity location = new MdmWarehouseLocationEntity();
        location.setLocationCode("T1");
        location.setLocationName("Target site");
        location.setWarehouseCode("W2");
        location.setFactoryCode("F1");
        location.setStatus(true);
        when(locations.getByCode("T1")).thenReturn(R.ok(location));
        ReflectionTestUtils.setField(service, "remoteWarehouseService", warehouses);
        ReflectionTestUtils.setField(service, "remoteWarehouseLocationService", locations);
        ReflectionTestUtils.setField(service, "wmsInoutFlowMapper", flows);
        ReflectionTestUtils.setField(service, "wmsLiveStockService", stocks);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        FactoryContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    void forgedFlowCannotMoveOrCreateStock() {
        when(flows.selectList(any(Wrapper.class))).thenReturn(List.of(issue()));
        WmsAllocationBillAddDTO command = command();
        command.getDetails().get(0).setFlowId(999L);

        assertThrows(CheckedException.class, () -> receive(command));

        verify(stocks, never()).transferOut(any(), any(), any());
        verify(stocks, never()).transferIn(any(), any(), any(), any(), any(), any());
        verify(stocks, never()).materialSaveOrUpdate(any());
        verify(flows, never()).insert(any(WmsInoutFlowEntity.class));
    }

    @Test
    void oldFlowWithoutSourceStockIdRequiresReconciliation() {
        WmsInoutFlowEntity issued = issue();
        issued.setSourceStockId(null);
        when(flows.selectList(any(Wrapper.class))).thenReturn(List.of(issued));

        CheckedException error = assertThrows(CheckedException.class, () -> receive(command()));

        assertTrue(error.getMessage().contains("缺少源库存ID"));
        verify(stocks, never()).transferOut(any(), any(), any());
        verify(stocks, never()).transferIn(any(), any(), any(), any(), any(), any());
    }

    @Test
    void partialOrExcessLegacyReceiptCannotMoveStock() {
        when(flows.selectList(any(Wrapper.class))).thenReturn(List.of(issue()));
        for (double quantity : List.of(1d, 3d)) {
            WmsAllocationBillAddDTO command = command();
            command.getDetails().get(0).setOperateNum(quantity);
            assertThrows(CheckedException.class, () -> receive(command));
        }
        verify(stocks, never()).transferOut(any(), any(), any());
        verify(stocks, never()).transferIn(any(), any(), any(), any(), any(), any());
    }

    @Test
    void historicalReceiptDeductsSourceAndCreditsTargetFromLockedSnapshot() {
        when(flows.selectList(any(Wrapper.class))).thenReturn(List.of(issue()));
        WmsLiveStockEntity stock = new WmsLiveStockEntity().setWarehouseCode("W1")
                .setSiteCode("S1").setMatCode("M1").setMatBatch("B1")
                .setMatUnit("EA").setMatNum(2d).setFactoryCode("F1");
        stock.setId(7L);
        stock.setTenantId(1L);
        when(stocks.transferOut(7L, 2d, "W1")).thenReturn(stock);
        when(flows.insert(any(WmsInoutFlowEntity.class))).thenReturn(1);
        doReturn(true).when(service).updateById(any(WmsAllocationBillEntity.class));

        assertTrue(receive(command()));

        verify(stocks).transferOut(7L, 2d, "W1");
        verify(stocks).transferIn(any(WmsInoutFlowEntity.class), eq("W2"), eq("Target"),
                eq("T1"), eq("Target site"), isNull());
        verify(stocks, never()).materialSaveOrUpdate(any());
        verify(flows).insert(argThat((WmsInoutFlowEntity flow) -> Long.valueOf(100L).equals(flow.getSourceFlowId())
                && Long.valueOf(7L).equals(flow.getSourceStockId())
                && "M1".equals(flow.getMatCode()) && "W2".equals(flow.getWarehouseCode())
                && Double.valueOf(2d).equals(flow.getMatNum())));
    }

    @Test
    void historicalIssueWithSourceStockInBillIdAlsoReceivesSafely() {
        WmsInoutFlowEntity issued = issue();
        issued.setBillId(7L);
        when(flows.selectList(any(Wrapper.class))).thenReturn(List.of(issued));
        WmsLiveStockEntity stock = new WmsLiveStockEntity().setWarehouseCode("W1")
                .setSiteCode("S1").setMatCode("M1").setMatBatch("B1")
                .setMatUnit("EA").setMatNum(2d).setFactoryCode("F1");
        stock.setId(7L);
        stock.setTenantId(1L);
        when(stocks.transferOut(7L, 2d, "W1")).thenReturn(stock);
        when(flows.insert(any(WmsInoutFlowEntity.class))).thenReturn(1);
        doReturn(true).when(service).updateById(any(WmsAllocationBillEntity.class));

        assertTrue(receive(command()));
        verify(stocks).transferOut(7L, 2d, "W1");
        verify(stocks).transferIn(any(WmsInoutFlowEntity.class), eq("W2"), eq("Target"),
                eq("T1"), eq("Target site"), isNull());
    }

    private boolean receive(WmsAllocationBillAddDTO command) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(service, "legacyReceive", bill(), command));
    }

    private WmsAllocationBillEntity bill() {
        WmsAllocationBillEntity bill = new WmsAllocationBillEntity().setBillNo("TR-7")
                .setOldWarehouseCode("W1").setNewWarehouseCode("W2")
                .setFactoryCode("F1");
        bill.setId(50L);
        return bill;
    }

    private WmsAllocationBillAddDTO command() {
        WmsDetailDTO line = new WmsDetailDTO();
        line.setFlowId(100L);
        line.setBillId(7L);
        line.setOperateNum(2d);
        line.setNewSiteCode("T1");
        WmsAllocationBillAddDTO command = new WmsAllocationBillAddDTO();
        command.setId(50L);
        command.setRequestId("receive-7");
        command.setDetails(List.of(line));
        return command;
    }

    private WmsInoutFlowEntity issue() {
        WmsInoutFlowEntity flow = new WmsInoutFlowEntity();
        flow.setId(100L);
        flow.setBillId(50L);
        flow.setSourceStockId(7L);
        flow.setBillNo("TR-7");
        flow.setTenantId(1L);
        flow.setFactoryCode("F1");
        flow.setWarehouseCode("W1");
        flow.setSiteCode("S1");
        flow.setMatCode("M1");
        flow.setMatBatch("B1");
        flow.setMatUnit("EA");
        flow.setMatNum(2d);
        flow.setOperateType(InOutEnum.OUT.getCode());
        flow.setBillType(OutBillTypeEnum.ALLOCATION_OUT.getCode());
        return flow;
    }
}
