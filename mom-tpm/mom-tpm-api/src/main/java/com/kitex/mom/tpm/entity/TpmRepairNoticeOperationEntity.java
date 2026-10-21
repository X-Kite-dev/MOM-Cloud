package com.kitex.mom.tpm.entity;

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
 * 维修提醒的不可变自动/人工处理履历。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("tpm_repair_notice_operation")
@EqualsAndHashCode(callSuper = true)
public class TpmRepairNoticeOperationEntity extends BaseEntity<TpmRepairNoticeOperationEntity> {
    private Long noticeId;
    private String requestId;
    private String requestFingerprint;
    private String action;
    private String beforeStatus;
    private String afterStatus;
    private Integer attemptNo;
    private String reason;
    private Long operatorId;
    private String operatorCode;
    private LocalDateTime operationTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
