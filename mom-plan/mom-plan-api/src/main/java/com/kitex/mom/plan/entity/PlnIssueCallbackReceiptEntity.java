package com.kitex.mom.plan.entity;

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
 * PLAN接收MES状态及数量回传的永久幂等凭证。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_issue_callback_receipt")
@EqualsAndHashCode(callSuper = true)
public class PlnIssueCallbackReceiptEntity extends BaseEntity<PlnIssueCallbackReceiptEntity> {
    private String requestId;
    private String requestFingerprint;
    private String sourceType;
    private Long issueId;
    private String issueNo;
    private String productCode;
    private String issueStatus;
    private String lifeCycleStatus;
    private BigDecimal finishQuantity;
    private BigDecimal unqualifiedQuantity;
    private BigDecimal qualityIsolatedQuantity;
    private BigDecimal qualityReleasedQuantity;
    private BigDecimal qualityRejectedQuantity;
    private BigDecimal scrapQuantity;
    private BigDecimal reworkQuantity;
    private BigDecimal reworkCompletedQuantity;
    private BigDecimal reworkQualifiedQuantity;
    private BigDecimal reworkRejectedQuantity;
    private LocalDateTime receivedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
