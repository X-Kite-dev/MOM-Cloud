package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@TenantTable
@TableName("mdm_duration_calibration_log")
@EqualsAndHashCode(callSuper = true)
public class MdmDurationCalibrationLogEntity extends BaseEntity<MdmDurationCalibrationLogEntity> {
    private String objectType;
    private Long objectId;
    private String action;
    private String fromStatus;
    private String toStatus;
    private String reason;
    private String operatedBy;
    private String operatedByName;
    private LocalDateTime operatedTime;
    private String beforeSnapshot;
    private String afterSnapshot;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
