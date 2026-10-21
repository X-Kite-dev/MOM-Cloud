package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("mes_counter_baseline_operation")
public class MesCounterBaselineOperation extends BaseEntity<MesCounterBaselineOperation> {
    private String factoryCode;
    private Long caseId;
    private Long ruleId;
    private String requestId;
    private String fingerprint;
    private String operationType;
    private String beforeStatus;
    private String afterStatus;
    private Long operatorId;
    private String operatorCode;
    private String reason;
}
