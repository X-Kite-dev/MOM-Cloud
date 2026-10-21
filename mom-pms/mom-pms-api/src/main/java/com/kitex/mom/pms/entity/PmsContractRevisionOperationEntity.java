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
 * 合同正文版本变更的永久幂等命令与不可变操作账。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pms_contract_revision_operation")
@EqualsAndHashCode(callSuper = true)
public class PmsContractRevisionOperationEntity extends BaseEntity<PmsContractRevisionOperationEntity> {
    private Long revisionRequestId;
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
