package com.kitex.mom.wms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * QMS驱动WMS质量库存状态的幂等命令。
 */
@Data
@Accessors(chain = true)
public class WmsQualityStatusCommand {
    private String requestId;
    private String holdNo;
    private String sourceType;
    private String sourceId;
    private String action;
    private String matCode;
    private String matBatch;
    /**
     * 数量对应的库存单位；召回等批量隔离必须按单位分别下达命令。
     */
    private String matUnit;
    private String supplierCode;
    private String warehouseCode;
    private String siteCode;
    private String containerCode;
    private BigDecimal quantity;
    private String reason;
    private String factoryCode;
}
