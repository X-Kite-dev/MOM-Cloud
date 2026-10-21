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
 * 客户实收逐出库明细冻结证据。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_shipment_receipt_detail")
@EqualsAndHashCode(callSuper = true)
public class WmsShipmentReceiptDetailEntity extends BaseEntity<WmsShipmentReceiptDetailEntity> {
    private Long custodyId;
    private Long outBillDetailId;
    private Long deliveryDetailId;
    private Long planId;
    private String planCode;
    private String orderCode;
    private Long orderDetailId;
    private String productCode;
    private BigDecimal shippedNum;
    private BigDecimal signedNum;
    private BigDecimal rejectedNum;
    private BigDecimal damagedNum;
    private BigDecimal lostNum;
    private String exceptionReason;
    private String responsibility;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
