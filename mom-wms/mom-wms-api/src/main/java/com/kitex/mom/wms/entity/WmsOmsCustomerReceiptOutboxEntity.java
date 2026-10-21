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
 * WMS客户实收事实到OMS的可靠投递箱。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_oms_customer_receipt_outbox")
@EqualsAndHashCode(callSuper = true)
public class WmsOmsCustomerReceiptOutboxEntity extends BaseEntity<WmsOmsCustomerReceiptOutboxEntity> {
    private String requestId;
    private String requestFingerprint;
    private Long custodyId;
    private String custodyNo;
    private Long outBillId;
    private String outBillNo;
    private Long planId;
    private String planCode;
    private String orderCode;
    private Long orderDetailId;
    private String productCode;
    private BigDecimal signedNum;
    private BigDecimal exceptionNum;
    private BigDecimal resolvedExceptionNum;
    private String shipmentType;
    private Long resolutionCaseId;
    private LocalDateTime receiptTime;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptTime;
    private String lastError;
    private String omsReceiptFingerprint;
    private BigDecimal omsSignedNum;
    private BigDecimal omsExceptionNum;
    private BigDecimal omsResolvedExceptionNum;
    private LocalDateTime completedTime;
    private String reconcileStatus;
    private String reconcileError;
    private LocalDateTime reconciledTime;
    private LocalDateTime nextReconcileTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
