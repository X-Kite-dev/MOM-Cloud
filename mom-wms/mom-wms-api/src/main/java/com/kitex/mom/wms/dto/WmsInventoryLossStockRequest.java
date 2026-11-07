package com.kitex.mom.wms.dto;

/**
 * 只声明来源、分配量和读取依据；实际库存属性由服务端冻结。
 */
public record WmsInventoryLossStockRequest(String stockId, String quantity, String basisVersion) {
}
