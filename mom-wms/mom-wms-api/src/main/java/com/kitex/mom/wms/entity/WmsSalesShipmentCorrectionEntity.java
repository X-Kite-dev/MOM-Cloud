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
import java.time.LocalDateTime;

/**
 * 销售出库整单红冲申请；库存未离场场景，申请、复核、执行永久留痕。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_sales_shipment_correction")
@EqualsAndHashCode(callSuper = true)
public class WmsSalesShipmentCorrectionEntity extends BaseEntity<WmsSalesShipmentCorrectionEntity> {
    private String correctionNo;
    private String requestId;
    private Long outBillId;
    private String outBillNo;
    private String state;
    private String reason;
    private BigDecimal totalNum;
    private String applicantCode;
    private String applicantName;
    private LocalDateTime appliedTime;
    private String reviewerCode;
    private String reviewerName;
    private LocalDateTime reviewedTime;
    private String rejectReason;
    private LocalDateTime executedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
