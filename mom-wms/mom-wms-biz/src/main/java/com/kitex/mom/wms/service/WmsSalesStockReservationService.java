package com.kitex.mom.wms.service;

import com.kitex.mom.wms.dto.WmsSalesStockReservationCommand;
import com.kitex.mom.wms.dto.WmsSalesStockReservationSnapshot;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsOutBillDetailEntity;
import com.kitex.mom.wms.entity.WmsSalesStockReservationEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface WmsSalesStockReservationService {
    String PREFIX = "SALE-";

    WmsSalesStockReservationSnapshot reserve(WmsSalesStockReservationCommand command);

    WmsSalesStockReservationSnapshot release(WmsSalesStockReservationCommand command);

    WmsSalesStockReservationSnapshot reallocateDrifted(WmsSalesStockReservationCommand command);

    WmsSalesStockReservationSnapshot snapshot(Long deliverPlanId);

    Map<Long, WmsSalesStockReservationSnapshot> snapshotsByPlanIds(Set<Long> deliverPlanIds);

    Map<Long, BigDecimal> activeReservedNums(Set<Long> liveStockIds);

    /** 返回指定销售预留在当前仓库可用于出库的库存行及其剩余预留数量。 */
    List<WmsLiveStockEntity> availableReservedStocks(String reservationNo, String productCode,
                                                      String warehouseCode);

    Map<Long, BigDecimal> activeReservedByPlanIds(Set<Long> deliverPlanIds);

    Map<Long, BigDecimal> healthyActiveReservedByPlanIds(Set<Long> deliverPlanIds);

    Map<Long, WmsSalesStockReservationEntity> reservationsByPlanIds(Set<Long> deliverPlanIds);

    String activeReservationNo(Long deliverPlanId);

    List<WmsLiveStockEntity> consume(WmsOutBillDetailEntity detail);

    /**
     * 红冲后把恢复的精确库存重新冻结给原销售计划；使用单调累计账，不回写历史消费。
     */
    void restoreAfterShipmentCorrection(Long deliverPlanId, List<WmsLiveStockEntity> restoredStocks,
                                        String requestId, String reason);

    void validateOutboundReservation(List<WmsOutBillDetailEntity> details);
}
