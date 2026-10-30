package com.kitex.mom.wms.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.dto.WmsDetailDTO;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WmsMoveLockedStockBoundaryTest {
    private final WmsLiveStockServiceImpl service = mock(WmsLiveStockServiceImpl.class, CALLS_REAL_METHODS);
    private final WmsLiveStockMapper stocks = mock(WmsLiveStockMapper.class);

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        ReflectionTestUtils.setField(service, "baseMapper", stocks);
    }

    @AfterEach
    void clearScope() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void lockedSourceWarehouseMustStillMatchMoveCommand() {
        when(stocks.selectOne(any(), eq(false))).thenReturn(stock("W2", "S1"));

        var error = assertThrows(CheckedException.class, () -> service.materialSaveOrUpdate(command()));

        assertEquals("移库源库存范围已变化，请刷新后重试", error.getMessage());
        verify(stocks, never()).deleteById(any(Long.class));
    }

    @Test
    void lockedSourceSiteMustStillMatchMoveCommand() {
        when(stocks.selectOne(any(), eq(false))).thenReturn(stock("W1", "S9"));

        var error = assertThrows(CheckedException.class, () -> service.materialSaveOrUpdate(command()));

        assertEquals("移库源库存范围已变化，请刷新后重试", error.getMessage());
        verify(stocks, never()).deleteById(any(Long.class));
    }

    private WmsDetailDTO command() {
        var command = new WmsDetailDTO();
        command.setBillId(1L);
        command.setMatNum(1d);
        command.setOldWarehouseCode("W1");
        command.setNewWarehouseCode("W1");
        command.setOldSiteCode("S1");
        command.setNewSiteCode("S2");
        return command;
    }

    private WmsLiveStockEntity stock(String warehouse, String site) {
        var stock = new WmsLiveStockEntity();
        stock.setWarehouseCode(warehouse);
        stock.setSiteCode(site);
        stock.setMatNum(5d);
        stock.setTenantId(1L);
        stock.setFactoryCode("F1");
        return stock;
    }
}
