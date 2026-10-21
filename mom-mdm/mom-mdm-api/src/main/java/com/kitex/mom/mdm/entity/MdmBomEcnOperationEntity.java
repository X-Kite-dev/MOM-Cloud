package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("mdm_bom_ecn_operation")
public class MdmBomEcnOperationEntity extends BaseEntity<MdmBomEcnOperationEntity> {
    private Long ecnId;
    private String requestId;
    private String requestFingerprint;
    private String action;
    private String reason;
    private Long operatorId;
    private String resultStatus;
    private LocalDateTime operationTime;
    private String factoryCode;
    private String roleCode;
    private String sourceFingerprint;
    private String reviewPolicy;
}
