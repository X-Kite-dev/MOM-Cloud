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

@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_oms_shipment_correction_outbox")
@EqualsAndHashCode(callSuper = true)
public class WmsOmsShipmentCorrectionOutboxEntity extends BaseEntity<WmsOmsShipmentCorrectionOutboxEntity> {
    private String requestId;
    private String requestFingerprint;
    private Long correctionId;
    private String correctionNo;
    private Long wmsOutBillId;
    private String wmsOutBillNo;
    private Long wmsPlanId;
    private String wmsPlanCode;
    private String orderCode;
    private Long orderDetailId;
    private String productCode;
    private BigDecimal correctedNum;
    private LocalDateTime correctedTime;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptTime;
    private String lastError;
    private BigDecimal omsDeliveredNum;
    private String omsReceiptFingerprint;
    private LocalDateTime completedTime;
    private String reconcileStatus;
    private String reconcileError;
    private LocalDateTime reconciledTime;
    private LocalDateTime nextReconcileTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
