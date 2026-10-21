package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** PMS采购驳回/取消等供给终止事件在PLAN侧的永久幂等回执。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("plan_mrp_purchase_status_receipt")
@EqualsAndHashCode(callSuper = true)
public class PlanMrpPurchaseStatusReceiptEntity extends BaseEntity<PlanMrpPurchaseStatusReceiptEntity> {
    private String eventId;
    private String requestFingerprint;
    private String sourceFingerprint;
    private String agentRequestId;
    private Long mrpId;
    private Long pmsOrderId;
    private String pmsOrderNo;
    private Long pmsOrderDetailId;
    private String pmsPlanNo;
    private String priorOrderStatus;
    private String resultStatus;
    private String rejectReason;
    private LocalDateTime rejectedTime;
    private LocalDateTime handledTime;
    private String message;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
