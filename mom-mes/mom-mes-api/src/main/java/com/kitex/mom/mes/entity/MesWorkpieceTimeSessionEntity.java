package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_workpiece_time_session")
@EqualsAndHashCode(callSuper = true)
public class MesWorkpieceTimeSessionEntity extends BaseEntity<MesWorkpieceTimeSessionEntity> {

    private String factoryCode;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long arrangeId;
    private String arrangeCode;
    private String taskCode;
    private String produceTaskCode;
    private String identifier;
    private String identifierType;
    private String workerCode;
    private String deviceCode;
    private String status;
    private String pauseSource;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long versionNo;
    private String captureBasis;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }
}
