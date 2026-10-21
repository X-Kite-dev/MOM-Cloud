package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("mes_labor_operation")
public class MesLaborOperation extends BaseEntity<MesLaborOperation> {
    private String factoryCode;
    private Long entryId;
    private String requestId;
    private String fingerprint;
    private String action;
    private String beforeStatus;
    private String afterStatus;
    private Long operatorId;
    private String reason;
    private String observedFingerprint;
}
