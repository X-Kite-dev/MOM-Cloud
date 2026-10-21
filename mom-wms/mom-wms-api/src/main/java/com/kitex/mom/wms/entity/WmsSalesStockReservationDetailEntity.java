package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 销售库存预留冻结到实时库存行的批次、仓库、库位与质量快照。
 */
@Data
@TenantTable
@TableName("wms_sales_stock_reservation_detail")
@EqualsAndHashCode(callSuper = true)
public class WmsSalesStockReservationDetailEntity extends BaseEntity<WmsSalesStockReservationDetailEntity> {
    private Long reservationId;
    private String reservationNo;
    private Long liveStockId;
    private String productCode;
    private String unitCode;
    private BigDecimal reservedNum;
    private BigDecimal consumedNum;
    private BigDecimal releasedNum;
    private String warehouseCode;
    private String warehouseName;
    private String siteCode;
    private String siteName;
    private String containerCode;
    private String matBatch;
    private String supplierCode;
    private String qualityStatus;
    private String qualityResult;
    private LocalDate validEndDate;
    private LocalDateTime stockInTime;
    private String status;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
