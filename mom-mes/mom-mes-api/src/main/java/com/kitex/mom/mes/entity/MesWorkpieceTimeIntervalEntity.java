package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_workpiece_time_interval")
@EqualsAndHashCode(callSuper = true)
public class MesWorkpieceTimeIntervalEntity extends BaseEntity<MesWorkpieceTimeIntervalEntity> {

    private String factoryCode;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sessionId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long arrangeId;
    private String arrangeCode;
    private String taskCode;
    private String produceTaskCode;
    private String deviceCode;
    private String workerCode;
    private String identifier;
    private String identifierType;
    private String kind;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String status;
    private String reasonCode;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }
}
