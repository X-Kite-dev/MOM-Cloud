package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("qms_mrb_operation")
public class QmsMrbOperation extends BaseEntity<QmsMrbOperation> {
    private String factoryCode;
    private Long proposalId;
    private Long detailId;
    private String requestId;
    private String fingerprint;
    private String operationType;
    private String roleCode;
    private String beforeStatus;
    private String afterStatus;
    private Integer revision;
    private Long operatorId;
    private String operatorCode;
    private String reason;
}
