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
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TenantTable
@TableName("mdm_tooling")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "统一工装主数据")
public class MdmToolingEntity extends BaseEntity<MdmToolingEntity> {
    private String toolingCode;
    private String toolingName;
    @Schema(description = "MOLD/TOOL/FIXTURE/GAUGE/AUXILIARY")
    private String toolingType;
    private String specification;
    private String model;
    @Schema(description = "AVAILABLE/IN_USE/MAINTENANCE/CALIBRATION/REPAIR/LOCKED/SCRAPPED")
    private String toolingState;
    @Schema(description = "CYCLE/HOUR/QUANTITY")
    private String lifeUnit;
    private BigDecimal designLife;
    private BigDecimal usedLife;
    private BigDecimal warningRemainingLife;
    private Boolean calibrationRequired;
    private LocalDate calibrationValidTo;
    private LocalDate maintenanceDueDate;
    private String location;
    private Boolean status;
    @Schema(description = "当前主状态开始不可排产的时刻；恢复可用后清空")
    private LocalDateTime unavailableSince;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
