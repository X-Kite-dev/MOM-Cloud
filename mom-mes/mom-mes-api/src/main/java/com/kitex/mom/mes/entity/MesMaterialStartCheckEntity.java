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
@TableName("mes_material_start_check")
@EqualsAndHashCode(callSuper = true)
public class MesMaterialStartCheckEntity extends BaseEntity<MesMaterialStartCheckEntity> {
    private String factoryCode;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long arrangeId;
    private String arrangeCode;
    private String taskCode;
    private String produceTaskCode;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorId;
    private String operatorCode;
    private String stage;
    private String mode;
    private String result;
    private String fingerprint;
    private String manifestJson;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }
}
