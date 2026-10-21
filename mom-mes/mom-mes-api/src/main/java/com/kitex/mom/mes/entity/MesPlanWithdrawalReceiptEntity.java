package com.kitex.mom.mes.entity;

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
 * MES开工前计划撤回的永久幂等回执。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_plan_withdrawal_receipt")
@EqualsAndHashCode(callSuper = true)
public class MesPlanWithdrawalReceiptEntity extends BaseEntity<MesPlanWithdrawalReceiptEntity> {
    private String requestId;
    private String requestFingerprint;
    private Long issueId;
    private String issueNo;
    private BigDecimal expectedQuantity;
    private String expectedEvidenceFingerprint;
    private String actualEvidenceFingerprint;
    private Integer closedTaskCount;
    private String closedTaskCodes;
    private Integer closedProduceTaskCount;
    private Integer closedArrangeCount;
    private Integer cancelledQmsTaskCount;
    private String qmsEvidenceFingerprint;
    private String status;
    private String reason;
    private String message;
    private LocalDateTime handledTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
