package com.kitex.mom.wms.service.outBill;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsOutBillDetailEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.mapper.WmsOutBillDetailMapper;
import com.kitex.mom.wms.mapper.WmsOutBillMapper;
import com.kitex.mom.wms.service.WmsLiveStockService;
import com.kitex.mom.wms.service.WmsMaterialReservationService;
import com.kitex.mom.wms.service.WmsSalesStockReservationService;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsOutBillExternalSourceWriteTest {

    @AfterEach
    void clearScope() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void directExternalBillPersistsSourceFromSelectedStock() {
        Fixture fixture = fixture();
        when(fixture.stocks.getById(91L)).thenReturn(externalStock());
        when(fixture.bills.insert(any(WmsOutBillEntity.class))).thenReturn(1);
        when(fixture.details.insert(any(WmsOutBillDetailEntity.class))).thenReturn(1);
        WmsOutBillEntity bill = externalBill();
        bill.setFactoryCode(null);

        fixture.service.saveDeep(bill);

        assertEquals("RECEIPT-1", bill.getDetails().get(0).getSourceBusinessNo());
        assertEquals("F1", bill.getFactoryCode());
        verify(fixture.details).insert(any(WmsOutBillDetailEntity.class));
    }

    @Test
    void directExternalBillRejectsConflictingSourceBeforeInsert() {
        Fixture fixture = fixture();
        when(fixture.stocks.getById(91L)).thenReturn(externalStock());
        WmsOutBillEntity bill = externalBill();
        bill.getDetails().get(0).setSourceBusinessNo("RECEIPT-2");

        assertThrows(CheckedException.class, () -> fixture.service.saveDeep(bill));

        verify(fixture.bills, never()).insert(any(WmsOutBillEntity.class));
    }

    @Test
    void directExternalBillRejectsStockWithoutInboundSourceBeforeInsert() {
        Fixture fixture = fixture();
        WmsLiveStockEntity stock = externalStock();
        stock.setSourceBusinessNo(null);
        when(fixture.stocks.getById(91L)).thenReturn(stock);

        assertThrows(CheckedException.class, () -> fixture.service.saveDeep(externalBill()));

        verify(fixture.bills, never()).insert(any(WmsOutBillEntity.class));
    }

    @Test
    void directExternalBillRejectsStockOutsideCurrentScopeBeforeInsert() {
        Fixture fixture = fixture();
        WmsLiveStockEntity stock = externalStock();
        stock.setTenantId(2L);
        when(fixture.stocks.getById(91L)).thenReturn(stock);

        assertThrows(CheckedException.class, () -> fixture.service.saveDeep(externalBill()));

        verify(fixture.bills, never()).insert(any(WmsOutBillEntity.class));
    }

    @Test
    void editingDirectBillRejectsChangedSourceBeforeReplacingLines() {
        Fixture fixture = fixture();
        when(fixture.stocks.getById(91L)).thenReturn(externalStock());
        WmsOutBillEntity existing = externalBill();
        existing.setId(7L);
        existing.setState("0");
        when(fixture.bills.selectById(7L)).thenReturn(existing);
        WmsOutBillDetailEntity replacement = externalBill().getDetails().get(0);
        replacement.setSourceBusinessNo("RECEIPT-2");

        assertThrows(CheckedException.class, () -> fixture.service.updateDetails(7L, List.of(replacement)));

        verify(fixture.details, never()).insert(any(WmsOutBillDetailEntity.class));
    }

    private static Fixture fixture() {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        DefaultWmsOutBillService service = new DefaultWmsOutBillService();
        WmsOutBillMapper bills = mock(WmsOutBillMapper.class);
        WmsOutBillDetailMapper details = mock(WmsOutBillDetailMapper.class);
        WmsLiveStockService stocks = mock(WmsLiveStockService.class);
        WmsWarehouseBusinessGuard guard = mock(WmsWarehouseBusinessGuard.class);
        when(guard.check("W1", "other_out", false)).thenReturn(true);
        ReflectionTestUtils.setField(service, "wmsOutBillMapper", bills);
        ReflectionTestUtils.setField(service, "wmsOutBillDetailMapper", details);
        ReflectionTestUtils.setField(service, "wmsLiveStockService", stocks);
        ReflectionTestUtils.setField(service, "warehouseBusinessGuard", guard);
        ReflectionTestUtils.setField(service, "materialReservationService", mock(WmsMaterialReservationService.class));
        ReflectionTestUtils.setField(service, "salesStockReservationService", mock(WmsSalesStockReservationService.class));
        return new Fixture(service, bills, details, stocks);
    }

    private static WmsOutBillEntity externalBill() {
        WmsOutBillDetailEntity detail = new WmsOutBillDetailEntity();
        detail.setMatCode("M1");
        detail.setMatUnit("EA");
        detail.setMatNum(1D);
        detail.setSourceStockId(91L);
        detail.setStockOwnerType("CONSIGNED");
        detail.setStockOwnerCode("SUPPLIER-1");
        WmsOutBillEntity bill = new WmsOutBillEntity();
        bill.setBillNo("OUT-1");
        bill.setBillType("other_out");
        bill.setWarehouseCode("W1");
        bill.setFactoryCode("F1");
        bill.setDetails(List.of(detail));
        return bill;
    }

    private static WmsLiveStockEntity externalStock() {
        WmsLiveStockEntity stock = new WmsLiveStockEntity();
        stock.setId(91L);
        stock.setTenantId(1L);
        stock.setFactoryCode("F1");
        stock.setWarehouseCode("W1");
        stock.setMatCode("M1");
        stock.setMatUnit("EA");
        stock.setStockOwnerType("CONSIGNED");
        stock.setStockOwnerCode("SUPPLIER-1");
        stock.setSourceBusinessNo("RECEIPT-1");
        stock.setMatNum(2D);
        stock.setStatus(0);
        return stock;
    }

    private record Fixture(DefaultWmsOutBillService service, WmsOutBillMapper bills,
                           WmsOutBillDetailMapper details, WmsLiveStockService stocks) {
    }
}
