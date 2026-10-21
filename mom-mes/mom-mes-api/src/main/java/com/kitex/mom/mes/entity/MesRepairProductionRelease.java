package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 一次维修停工对应的不可变复产申请/异岗决策。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("mes_repair_production_release")
public class MesRepairProductionRelease extends BaseEntity<MesRepairProductionRelease> {
    private String factoryCode;
    private Long stopId;
    private Long arrangeId;
    private Long previousId;
    private String action;
    private String requestId;
    private String commandFingerprint;
    private String evidenceFingerprint;
    private String evidenceJson;
    private String checksJson;
    private Long operatorId;
    private String operatorName;
    private String reason;
    private LocalDateTime operationTime;
}
