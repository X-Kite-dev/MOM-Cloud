package com.kitex.mom.oms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** OMS跨服务撤回协调账；WMS回执与OMS本地结果可分别恢复和对账。 */
@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("oms_delivery_withdrawal")
public class OmsDeliveryWithdrawalEntity extends BaseEntity<OmsDeliveryWithdrawalEntity> {
    private String requestId;
    private String orderCode;
    private Long orderDetailId;
    private String originalState;
    private String omsLineSnapshot;
    private Long demandId;
    private String demandSnapshot;
    private Long wmsPlanId;
    private String wmsPlanSnapshot;
    private String wmsRequestId;
    private String wmsReceiptFingerprint;
    private String reason;
    private String status;
    private String lastError;
    private Integer attemptCount;
    private LocalDateTime nextAttemptTime;
    private String applicantCode;
    private String applicantName;
    private LocalDateTime submittedTime;
    private LocalDateTime wmsCompletedTime;
    private LocalDateTime completedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
