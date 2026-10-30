package com.kitex.mom.wms.controller;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsLiveStockQuerySumDTO;
import com.kitex.mom.wms.service.WmsLiveStockService;
import com.kitex.mom.wms.service.impl.WmsLiveStockReadAccess;
import com.kitex.mom.wms.service.impl.WmsOperationStockSummaryAccess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WmsOperationStockSummaryControllerTest {
    @Test
    void summaryRejectsUnauthorizedOperationBeforeReadingInventory() {
        var stocks = mock(WmsLiveStockService.class);
        var access = mock(WmsOperationStockSummaryAccess.class);
        var controller = new WmsMaterialLiveStockController(stocks, mock(WmsLiveStockReadAccess.class), access);
        var query = new WmsLiveStockQuerySumDTO();
        query.setWarehouseCode("W1");
        query.setSiteCode("L1");
        query.setMatCode("M1");

        doThrow(new CheckedException("无权操作")).when(access).require("move_site", "W1");

        assertThrows(CheckedException.class, () -> controller.getSumByParams(query, "move_site"));
        verify(stocks, never()).getSumByParams(query);

        controller.getSumByParams(query, "allocation_out");
        verify(access).require("allocation_out", "W1");
        verify(stocks).getSumByParams(query);
    }
}
