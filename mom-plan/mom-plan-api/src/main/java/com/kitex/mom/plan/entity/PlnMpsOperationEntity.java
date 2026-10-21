package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** MPS政策、候选与发布的不可变操作履历。 */
@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("pln_mps_operation")
public class PlnMpsOperationEntity extends BaseEntity<PlnMpsOperationEntity> {
    private Long versionId;
    private Long policyId;
    private String requestId;
    private String requestFingerprint;
    private String operationType;
    private String beforeStatus;
    private String afterStatus;
    private String detail;
    private String operatorCode;
    private LocalDateTime operationTime;
    private String factoryCode;
}
