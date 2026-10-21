package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 销售库存预留不可变命令履历。
 */
@Data
@TenantTable
@TableName("wms_sales_stock_reservation_operation")
@EqualsAndHashCode(callSuper = true)
public class WmsSalesStockReservationOperationEntity extends BaseEntity<WmsSalesStockReservationOperationEntity> {
    private Long reservationId;
    private Long deliverPlanId;
    private String requestId;
    private String operationType;
    private String beforeStatus;
    private String afterStatus;
    private BigDecimal quantity;
    private String reason;
    private String beforeSnapshot;
    private String afterSnapshot;
    private String operatorCode;
    private String operatorName;
    private LocalDateTime operationTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
