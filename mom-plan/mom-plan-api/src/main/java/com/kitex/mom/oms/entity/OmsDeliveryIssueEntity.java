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

/** OMS交付行下发协调账，持有WMS冻结命令与PLAN本地提交证据。 */
@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("oms_delivery_issue")
public class OmsDeliveryIssueEntity extends BaseEntity<OmsDeliveryIssueEntity> {
    private String requestId;
    private String mode;
    private String orderCode;
    private Long orderDetailId;
    private String originalState;
    private String omsLineSnapshot;
    private Long demandId;
    private String demandSnapshot;
    private String demandNo;
    private String wmsAction;
    private String wmsBeforeSnapshot;
    private String wmsDesiredSnapshot;
    private String wmsRequestId;
    private Long wmsPlanId;
    private String wmsReceiptFingerprint;
    private String status;
    private String lastError;
    private Integer attemptCount;
    private LocalDateTime nextAttemptTime;
    private String applicantCode;
    private LocalDateTime submittedTime;
    private LocalDateTime wmsCompletedTime;
    private LocalDateTime completedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
