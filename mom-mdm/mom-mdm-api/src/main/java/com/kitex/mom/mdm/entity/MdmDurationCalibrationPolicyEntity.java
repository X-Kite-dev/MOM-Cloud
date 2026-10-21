package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TenantTable
@TableName("mdm_duration_calibration_policy")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "实际工时校准版本策略")
public class MdmDurationCalibrationPolicyEntity extends BaseEntity<MdmDurationCalibrationPolicyEntity> {
    private String policyNo;
    private String policyName;
    private Integer versionNo;
    @Schema(description = "OPERATION/CHANGEOVER")
    private String targetType;
    @Schema(description = "PROCESS/STATION/DEVICE/TOOLING")
    private String scopeType;
    private String processCode;
    private String resourceCode;
    private Integer lookbackDays;
    private Integer minSampleCount;
    private Integer maxSampleCount;
    @Schema(description = "MAD_RATE/NONE")
    private String outlierMethod;
    private BigDecimal outlierThreshold;
    @Schema(description = "回归样本最大/最小数量差异百分比")
    private BigDecimal minQuantitySpreadPct;
    @Schema(description = "建议相对当前标准最大允许偏差百分比")
    private BigDecimal maxDeviationPct;
    @Schema(description = "DRAFT/ACTIVE/RETIRED")
    private String status;
    private String approvedBy;
    private String approvedByName;
    private LocalDateTime approvedTime;
    private String closeReason;
    private String closedBy;
    private String closedByName;
    private LocalDateTime closedTime;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
