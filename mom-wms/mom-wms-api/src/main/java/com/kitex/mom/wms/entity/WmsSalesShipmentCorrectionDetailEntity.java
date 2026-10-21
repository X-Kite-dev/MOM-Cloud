package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * 红冲执行明细，一行对应原始发运消耗的一条精确库存证据。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_sales_shipment_correction_detail")
@EqualsAndHashCode(callSuper = true)
public class WmsSalesShipmentCorrectionDetailEntity extends BaseEntity<WmsSalesShipmentCorrectionDetailEntity> {
    private Long correctionId;
    private Long outBillDetailId;
    private Long sourceFlowId;
    private Long sourceStockId;
    private Long restoredStockId;
    private Long deliverDetailId;
    private Long deliverPlanId;
    private String deliverPlanCode;
    private String orderCode;
    private Long orderDetailId;
    private String productCode;
    private String warehouseCode;
    private String siteCode;
    private String containerCode;
    private String matBatch;
    private BigDecimal correctedNum;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
