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

/**
 * 风险催办持久发件箱；结果不明不得自动重发。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("oms_fulfillment_risk_notice")
@EqualsAndHashCode(callSuper = true)
public class OmsFulfillmentRiskNoticeEntity extends BaseEntity<OmsFulfillmentRiskNoticeEntity> {
    private Long caseId;
    private Long orderDetailId;
    private String caseNo;
    private String orderCode;
    private String productCode;
    private String riskName;
    private String noticeKey;
    private String category;
    private Long recipientUserId;
    private String recipientCode;
    private String recipientName;
    private String businessCode;
    private String message;
    private String status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private LocalDateTime nextAttemptTime;
    private LocalDateTime claimedTime;
    private LocalDateTime deliveredTime;
    private String lastError;
    private String lastOperationRequestId;
    private String lastOperationType;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
