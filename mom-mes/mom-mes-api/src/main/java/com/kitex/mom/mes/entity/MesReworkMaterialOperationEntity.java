package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TenantTable
@TableName("mes_rework_material_operation")
@EqualsAndHashCode(callSuper = true)
public class MesReworkMaterialOperationEntity extends BaseEntity<MesReworkMaterialOperationEntity> {
    private String factoryCode;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long planId;
    private String requestId;
    private String fingerprint;
    private String operationType;
    private String beforeStatus;
    private String afterStatus;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorId;
    private String operatorCode;
    private String reason;
    private String executionSnapshot;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }
}
