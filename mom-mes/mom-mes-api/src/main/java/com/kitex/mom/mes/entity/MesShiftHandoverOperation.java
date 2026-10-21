package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("mes_shift_handover_operation")
public class MesShiftHandoverOperation extends BaseEntity<MesShiftHandoverOperation> {
    private String factoryCode;
    private Long handoverId;
    private String requestId;
    private String fingerprint;
    private String action;
    private String resultStatus;
    private Long operatorId;
    private String reason;
    private String observedFingerprint;
}
