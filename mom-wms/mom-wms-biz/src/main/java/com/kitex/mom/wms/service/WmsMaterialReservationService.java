package com.kitex.mom.wms.service;

import com.kitex.mom.wms.dto.*;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsOutBillDetailEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface WmsMaterialReservationService {
    WmsMaterialReservationResultDTO reserve(WmsMaterialReservationCreateDTO command);

    Boolean release(WmsMaterialReservationReleaseDTO command);

    Boolean reallocate(WmsMaterialReservationReallocateDTO command);

    BigDecimal activeReservedNum(Long liveStockId);

    Map<Long, BigDecimal> activeReservedNums(Set<Long> liveStockIds);

    List<WmsLiveStockEntity> availableReservedStocks(String reservationNo, String actualMatCode, String warehouseCode);

    void validateOutboundReservation(List<WmsOutBillDetailEntity> details);

    List<WmsLiveStockEntity> consume(WmsOutBillDetailEntity detail);

    Boolean replenishFromProductionReturn(WmsInBillDetailEntity detail, Long liveStockId);

    Map<Long, String> statuses(String sourceType, Set<Long> sourceIds);

    List<WmsMaterialReservationLedgerDTO> ledgers(Set<String> reservationNos);

    WmsMaterialCompensationResultDTO compensate(WmsMaterialCompensationCommandDTO command);
}
