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
 * 合同完整正文与条款的不可变版本。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pms_contract_version")
@EqualsAndHashCode(callSuper = true)
public class PmsContractVersionEntity extends BaseEntity<PmsContractVersionEntity> {
    private Long contractId;
    private String contractNo;
    private Integer versionNo;
    /**
     * BASELINE/REVISION。
     */
    private String sourceType;
    private Long revisionRequestId;
    /**
     * ACTIVE/SUPERSEDED。
     */
    private String status;
    private String contentSnapshot;
    private String contentFingerprint;
    private LocalDateTime effectiveTime;
    private LocalDateTime supersededTime;
    private String approvedBy;
    private String approvedByName;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
