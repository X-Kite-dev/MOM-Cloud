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
 * 客户异常退款/让步结案到OMS的可靠Outbox。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_oms_customer_exception_resolution_outbox")
@EqualsAndHashCode(callSuper = true)
public class WmsOmsCustomerExceptionResolutionOutboxEntity extends BaseEntity<WmsOmsCustomerExceptionResolutionOutboxEntity> {
    private String requestId;
    private String requestFingerprint;
    private Long resolutionCaseId;
    private String caseNo;
    private String orderCode;
    private Long orderDetailId;
    private String productCode;
    private BigDecimal refundNum;
    private BigDecimal concessionNum;
    private BigDecimal refundAmount;
    private BigDecimal concessionAmount;
    private String refundReference;
    private String concessionReference;
    private String evidence;
    private LocalDateTime settlementTime;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptTime;
    private String lastError;
    private String omsReceiptFingerprint;
    private BigDecimal omsResolvedExceptionNum;
    private BigDecimal omsSettlementResolvedNum;
    private LocalDateTime completedTime;
    private String reconcileStatus;
    private String reconcileError;
    private LocalDateTime reconciledTime;
    private LocalDateTime nextReconcileTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
