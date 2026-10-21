package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("mes_device_gate_record")
@Schema(description = "MES设备运行门判定审计")
public class MesDeviceGateRecordEntity extends BaseEntity<MesDeviceGateRecordEntity> {

    private String stage;
    private Boolean allowed;
    private String arrangeCode;
    private String produceTaskCode;
    private String taskCode;
    private String worksiteCode;
    private String deviceCode;
    private String deviceName;
    private String deviceState;
    private String deviceStateName;
    private String message;
    private String unavailableWindowSnapshot;
    private String operatorCode;
    private String operatorName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime checkTime;

    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
