package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.dto.WmsDetailDTO;
import com.kitex.mom.wms.dto.WmsMaterialMoveSiteAddDTO;
import com.kitex.mom.wms.dto.app.WmsMaterialMoveSiteAppDTO;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsMaterialMoveSiteEntity;
import com.kitex.mom.wms.service.WmsLiveStockService;
import com.kitex.mom.wms.service.WmsStockMoveSlice;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WmsMaterialMoveSiteConfigurationTest {
    private final WmsMaterialMoveSiteServiceImpl service = mock(WmsMaterialMoveSiteServiceImpl.class, CALLS_REAL_METHODS);
    private final WmsWarehouseBusinessGuard guard = mock(WmsWarehouseBusinessGuard.class);
    private final RemoteWarehouseService warehouses = mock(RemoteWarehouseService.class);
    private final RemoteWarehouseLocationService locations = mock(RemoteWarehouseLocationService.class);
    private final WmsLiveStockService stocks = mock(WmsLiveStockService.class);

    @BeforeEach
    void setUp() {
        FactoryContextHolder.setFactoryCode("F1");
        TenantContextHolder.setTenantId(1L);
        ReflectionTestUtils.setField(service, "warehouseBusinessGuard", guard);
        ReflectionTestUtils.setField(service, "remoteWarehouseService", warehouses);
        ReflectionTestUtils.setField(service, "remoteWarehouseLocationService", locations);
        ReflectionTestUtils.setField(service, "wmsLiveStockService", stocks);
        when(warehouses.getByCode("W1")).thenReturn(R.ok(warehouse("W1")));
        when(locations.getByCode("S2")).thenReturn(R.ok(location("S2")));
        when(guard.check("W1", "move_site", false)).thenReturn(true);
    }

    @AfterEach
    void clearScope() {
        FactoryContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    void desktopMoveMustRespectWarehouseOperationConfiguration() {
        doThrow(new CheckedException("未配置库内移动")).when(guard).check("W1", "move_site", false);

        var error = assertThrows(CheckedException.class, () -> service.materialMoveSiteSave(desktopCommand()));

        assertEquals("未配置库内移动", error.getMessage());
        verify(stocks, never()).materialSaveOrUpdate(any());
    }

    @Test
    void mobileMoveMustRespectWarehouseOperationConfiguration() {
        doThrow(new CheckedException("未配置库内移动")).when(guard).check("W1", "move_site", false);
        var command = new WmsMaterialMoveSiteAppDTO();
        command.setWarehouseCode("W1");
        command.setRequestId("request-move-config");
        command.setBillNo("MOVE-CONFIG");
        command.setOperateNum(1d);

        var error = assertThrows(CheckedException.class, () -> service.appMaterialMoveSiteSave(command));

        assertEquals("未配置库内移动", error.getMessage());
        verify(stocks, never()).billStockMoveWithEvidence(any());
    }

    @Test
    void desktopMoveCannotUseStockFromAnotherWarehouse() {
        var stock = new WmsLiveStockEntity().setWarehouseCode("W2").setSiteCode("S1");
        stock.setTenantId(1L);
        stock.setFactoryCode("F1");
        when(stocks.getOne(any(Wrapper.class), eq(false))).thenReturn(stock);

        var error = assertThrows(CheckedException.class, () -> service.materialMoveSiteSave(desktopCommand()));

        assertEquals("源库存不属于当前仓库", error.getMessage());
        verify(stocks, never()).materialSaveOrUpdate(any());
    }

    @Test
    void desktopMovePersistsValidatedWarehouseAndSourceSnapshot() {
        var stock = new WmsLiveStockEntity().setWarehouseCode("W1").setSiteCode("S1").setSiteName("Old Site");
        stock.setMatCode("M1");
        stock.setSourceInBillDetailId(81L);
        stock.setStockOwnerType("CUSTOMER_SUPPLIED");
        stock.setStockOwnerCode("C1");
        stock.setSourceBusinessNo("ORDER-1");
        stock.setId(1L);
        stock.setTenantId(1L);
        stock.setFactoryCode("F1");
        when(stocks.getOne(any(Wrapper.class), eq(false))).thenReturn(stock);
        when(stocks.materialSaveOrUpdate(any())).thenReturn(new WmsStockMoveSlice(stock, 9L, 1d));
        doReturn(true).when(service).save(any(WmsMaterialMoveSiteEntity.class));
        var command = desktopCommand();
        command.setBillNo("MOVE-7");
        command.getDetails().get(0).setMatCode("FORGED");

        assertEquals(Boolean.TRUE, service.materialMoveSiteSave(command));

        var record = org.mockito.ArgumentCaptor.forClass(WmsMaterialMoveSiteEntity.class);
        verify(service).save(record.capture());
        assertEquals("MOVE-7", record.getValue().getBillNo());
        assertEquals("W1", record.getValue().getWarehouseCode());
        assertEquals("M1", record.getValue().getMatCode());
        assertEquals("F1", record.getValue().getFactoryCode());
        assertEquals(1L, record.getValue().getSourceStockId());
        assertEquals(9L, record.getValue().getTargetStockId());
        assertEquals(81L, record.getValue().getSourceInBillDetailId());
        assertEquals("CUSTOMER_SUPPLIED", record.getValue().getStockOwnerType());
        assertEquals("C1", record.getValue().getStockOwnerCode());
        assertEquals("ORDER-1", record.getValue().getSourceBusinessNo());
        var moved = org.mockito.ArgumentCaptor.forClass(WmsDetailDTO.class);
        verify(stocks).materialSaveOrUpdate(moved.capture());
        assertEquals("W1", moved.getValue().getOldWarehouseCode());
        assertEquals("W1", moved.getValue().getNewWarehouseCode());
        assertEquals("S1", moved.getValue().getOldSiteCode());
        assertEquals("S2", moved.getValue().getNewSiteCode());
    }

    private WmsMaterialMoveSiteAddDTO desktopCommand() {
        var command = new WmsMaterialMoveSiteAddDTO();
        command.setWarehouseCode("W1");
        var detail = new WmsDetailDTO();
        detail.setBillId(1L);
        detail.setMatNum(1d);
        detail.setNewSiteCode("S2");
        command.setDetails(List.of(detail));
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

    private MdmWarehouseLocationEntity location(String code) {
        var site = new MdmWarehouseLocationEntity();
        site.setLocationCode(code);
        site.setLocationName(code);
        site.setWarehouseCode("W1");
        site.setFactoryCode("F1");
        site.setTenantId(1L);
        site.setLocationLevel(3);
        site.setStatus(true);
        return site;
    }
}
