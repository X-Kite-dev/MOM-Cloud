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
 * 销售出库WMS事务内写入的OMS交付事实待投递账。
 */
@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("wms_oms_delivery_event_outbox")
public class WmsOmsDeliveryEventOutboxEntity extends BaseEntity<WmsOmsDeliveryEventOutboxEntity> {
    private String requestId;
    private String requestFingerprint;
    private Long wmsBillId;
    private String wmsBillNo;
    private Long wmsPlanId;
    private String wmsPlanCode;
    private String orderCode;
    private Long orderDetailId;
    private String productCode;
    private BigDecimal shippedNum;
    private LocalDateTime shippedTime;
    private String shipmentType;
    private Long resolutionCaseId;
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
