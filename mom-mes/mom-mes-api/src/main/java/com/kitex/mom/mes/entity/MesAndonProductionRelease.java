package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("mes_andon_production_release")
public class MesAndonProductionRelease extends BaseEntity<MesAndonProductionRelease> {
    private Long recordId;
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
    private String factoryCode;
}
