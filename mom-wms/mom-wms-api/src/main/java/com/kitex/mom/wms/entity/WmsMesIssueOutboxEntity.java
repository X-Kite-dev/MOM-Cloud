package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@TenantTable
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("wms_mes_issue_outbox")
public class WmsMesIssueOutboxEntity extends BaseEntity<WmsMesIssueOutboxEntity> {
    private String eventId;
    private Long wmsBillId;
    private String wmsBillNo;
    private Long pickingBillId;
    private String fingerprint;
    private String payloadJson;
    private Integer detailCount;
    private String status;
    private Integer attemptCount;
    private Integer cycleAttempts;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime nextAttemptTime;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String leaseToken;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime leaseUntil;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;
    private String receiptJson;
    private LocalDateTime deliveredTime;
    private String factoryCode;
}
