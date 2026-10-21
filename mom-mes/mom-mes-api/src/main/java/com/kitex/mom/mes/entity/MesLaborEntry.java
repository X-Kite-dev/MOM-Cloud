package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("mes_labor_entry")
public class MesLaborEntry extends BaseEntity<MesLaborEntry> {
    private String factoryCode;
    private Long segmentId;
    private Long arrangeId;
    private String arrangeCode;
    private String taskCode;
    private String produceTaskCode;
    private Long workerId;
    private String workerCode;
    private String workerName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime laborStart;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime laborEnd;
    private Long durationSeconds;
    private Long correctedFromId;
    private String status;
    private String sourceJson;
    private String sourceFingerprint;
    private String reason;
    private Long operatorId;
    private String operationReason;
}
