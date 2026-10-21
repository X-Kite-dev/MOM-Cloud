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
 * 销售退货入库审核与OMS反向恢复之间的可靠Outbox。
 */
@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("wms_oms_sales_return_event_outbox")
public class WmsOmsSalesReturnEventOutboxEntity extends BaseEntity<WmsOmsSalesReturnEventOutboxEntity> {
    private String requestId;
    private String requestFingerprint;
    private Long wmsInBillId;
    private Long wmsInBillDetailId;
    private String wmsInBillNo;
    private Long wmsReturnId;
    private Long resolutionCaseId;
    private Long wmsReturnDetailId;
    private Long wmsPlanId;
    private String wmsPlanCode;
    private String orderCode;
    private Long orderDetailId;
    private String productCode;
    private BigDecimal returnedNum;
    private LocalDateTime returnedTime;
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
