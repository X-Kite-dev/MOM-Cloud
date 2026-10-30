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
 * OMS客户异常退款/让步结案永久收件箱。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("oms_customer_exception_resolution_event")
@EqualsAndHashCode(callSuper = true)
public class OmsCustomerExceptionResolutionEventEntity extends BaseEntity<OmsCustomerExceptionResolutionEventEntity> {
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
    private BigDecimal omsResolvedExceptionNum;
    private BigDecimal omsSettlementResolvedNum;
    private String status;
    private LocalDateTime completedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
