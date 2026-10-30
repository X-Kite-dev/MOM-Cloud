package com.kitex.mom.oms.entity;

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
 * OMS客户实收永久收件箱。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("oms_customer_receipt_event")
@EqualsAndHashCode(callSuper = true)
public class OmsCustomerReceiptEventEntity extends BaseEntity<OmsCustomerReceiptEventEntity> {
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
    private BigDecimal omsSignedNum;
    private BigDecimal omsExceptionNum;
    private BigDecimal omsResolvedExceptionNum;
    private LocalDateTime receiptTime;
    private String status;
    private LocalDateTime completedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
