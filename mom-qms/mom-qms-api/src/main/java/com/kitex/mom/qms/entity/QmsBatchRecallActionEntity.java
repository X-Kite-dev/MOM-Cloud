package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 批次召回中的库存隔离或人工客户通知动作及永久回执。
 */
@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("qms_batch_recall_action")
public class QmsBatchRecallActionEntity extends BaseEntity<QmsBatchRecallActionEntity> {
    private Long recallId;
    private String actionNo;
    private String requestId;
    private String actionType;
    private String targetType;
    private String targetReference;
    private String materialUnit;
    private BigDecimal requestedQuantity;
    private BigDecimal affectedQuantity;
    private String status;
    private Long remoteOperationId;
    private String remoteReference;
    private String resultMessage;
    private String evidence;
    private String evidenceHash;
    private Integer attemptCount;
    private String executedBy;
    private String executedByName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime executedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
