package com.kitex.mom.wms.service;

import com.kitex.mom.wms.entity.WmsLiveStockEntity;

/**
 * 一次库存移动实际消耗的来源行与生成的目标行。
 */
public record WmsStockMoveSlice(WmsLiveStockEntity sourceStock, Long targetStockId, double quantity) {
}
