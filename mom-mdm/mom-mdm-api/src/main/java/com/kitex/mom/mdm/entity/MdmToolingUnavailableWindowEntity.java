package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@TenantTable
@TableName("mdm_tooling_unavailable_window")
@EqualsAndHashCode(callSuper = true)
public class MdmToolingUnavailableWindowEntity extends BaseEntity<MdmToolingUnavailableWindowEntity> {
    private String toolingCode;
    private String toolingName;
    @Schema(description = "MAINTENANCE/CALIBRATION/REPAIR/LOCK")
    private String windowType;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String reason;
    private Boolean status;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
