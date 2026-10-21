package com.kitex.mom.qms.entity;

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
 * MES来源工序质检任务受控作废的永久幂等回执。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("qms_procedure_source_withdrawal_receipt")
@EqualsAndHashCode(callSuper = true)
public class QmsProcedureSourceWithdrawalReceiptEntity extends BaseEntity<QmsProcedureSourceWithdrawalReceiptEntity> {
    private String requestId;
    private String requestFingerprint;
    private String taskSetFingerprint;
    private String sourceTaskCodes;
    private String taskIds;
    private Integer cancelledTaskCount;
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
