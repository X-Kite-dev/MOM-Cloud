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
 * 报修即时通知与分级超时升级的永久投递账。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("tpm_repair_notice")
@EqualsAndHashCode(callSuper = true)
public class TpmRepairNoticeEntity extends BaseEntity<TpmRepairNoticeEntity> {
    private Long taskId;
    private String noticeKey;
    private LocalDateTime dueTime;
    private String ruleCodesJson;
    private String userIdsJson;
    private String wxUserIds;
    private String message;
    /**
     * PENDING、IN_FLIGHT、DELIVERED、UNCERTAIN、CANCELLED。
     */
    private String status;
    private Integer attemptCount;
    private LocalDateTime claimedTime;
    private LocalDateTime deliveredTime;
    private String lastError;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
