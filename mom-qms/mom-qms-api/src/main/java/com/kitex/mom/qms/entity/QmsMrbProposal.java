package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("qms_mrb_proposal")
public class QmsMrbProposal extends BaseEntity<QmsMrbProposal> {
    private String factoryCode;
    private Long badId;
    private Long detailId;
    private String policyVersion;
    private String requiredRoles;
    private String commandJson;
    private String sourceSnapshot;
    private String sourceFingerprint;
    private String reason;
    private Long applicantId;
    private String status;
    private Integer revision;
    private LocalDateTime expiresTime;
    private String executionRequestId;
    private String executionFingerprint;
    private Long executorId;
    private LocalDateTime executionTime;
}
