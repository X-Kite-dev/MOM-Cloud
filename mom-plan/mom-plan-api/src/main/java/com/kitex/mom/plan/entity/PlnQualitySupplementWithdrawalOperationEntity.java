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

/** 补产撤回不可变操作履历，兼作所有人工命令的永久幂等账。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_quality_supplement_withdrawal_operation")
@EqualsAndHashCode(callSuper = true)
public class PlnQualitySupplementWithdrawalOperationEntity extends BaseEntity<PlnQualitySupplementWithdrawalOperationEntity> {
    private Long withdrawalId;
    private String requestId;
    private String requestFingerprint;
    private String operationType;
    private String beforeStatus;
    private String afterStatus;
    private String content;
    private String operatorCode;
    private String operatorName;
    private LocalDateTime operationTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
