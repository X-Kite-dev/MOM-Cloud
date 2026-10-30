package com.kitex.mom.wms.util;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WmsSerialStockGuardTest {

    @Test
    void qualityActionCannotSplitSerializedStock() {
        var stock = new WmsLiveStockEntity().setMatNum(1d)
                .setMatBarcode("SN-1").setSerialManaged(true);

        assertThrows(CheckedException.class,
                () -> WmsSerialStockGuard.requireWhole(stock, new BigDecimal("0.5")));
        assertDoesNotThrow(() -> WmsSerialStockGuard.requireWhole(stock, BigDecimal.ONE));
    }

    @Test
    void malformedSerializedStockRequiresReconciliationBeforeMovement() {
        var stock = new WmsLiveStockEntity().setMatNum(2d)
                .setMatBarcode("SN-1").setSerialManaged(true);

        assertThrows(CheckedException.class,
                () -> WmsSerialStockGuard.requireWhole(stock, BigDecimal.ONE));
    }
}
