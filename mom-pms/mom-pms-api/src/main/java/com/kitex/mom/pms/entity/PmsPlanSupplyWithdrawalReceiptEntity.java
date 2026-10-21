package com.kitex.mom.pms.entity;

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
 * PMS供应计划撤回永久幂等回执。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pms_plan_supply_withdrawal_receipt")
@EqualsAndHashCode(callSuper = true)
public class PmsPlanSupplyWithdrawalReceiptEntity extends BaseEntity<PmsPlanSupplyWithdrawalReceiptEntity> {
    private String requestId;
    private String requestFingerprint;
    private Long sourceIssueMonthId;
    private Long pmsPlanId;
    private String pmsPlanNo;
    private String expectedEvidenceFingerprint;
    private String actualEvidenceFingerprint;
    private String evidenceSnapshot;
    private String status;
    private String reason;
    private String message;
    private LocalDateTime handledTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
