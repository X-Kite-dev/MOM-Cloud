package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 销售发货计划对合格成品库存的正式数量预留。
 */
@Data
@TenantTable
@TableName("wms_sales_stock_reservation")
@EqualsAndHashCode(callSuper = true)
public class WmsSalesStockReservationEntity extends BaseEntity<WmsSalesStockReservationEntity> {
    private String reservationNo;
    private Long deliverPlanId;
    private String deliverPlanCode;
    private String orderCode;
    private Long orderDetailId;
    private String customerCode;
    private String productCode;
    private String unitCode;
    private BigDecimal targetNum;
    private BigDecimal reservedNum;
    private BigDecimal consumedNum;
    private BigDecimal releasedNum;
    private String status;
    private String allocationPolicy;
    private String lastRequestId;
    private String lastOperationType;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
