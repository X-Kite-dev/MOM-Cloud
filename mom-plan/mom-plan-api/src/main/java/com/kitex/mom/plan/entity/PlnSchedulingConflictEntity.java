package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 排产冲突
 */
@Data
@TenantTable
@TableName("pln_scheduling_conflict")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "排产冲突")
public class PlnSchedulingConflictEntity extends BaseEntity<PlnSchedulingConflictEntity> {

    @Schema(description = "任务ID")
    private Long taskId;

    @Schema(description = "冲突类型")
    private String conflictType;

    @Schema(description = "冲突级别")
    private String conflictLevel;

    @Schema(description = "是否硬约束")
    private Boolean hardConstraint;

    @Schema(description = "计划ID")
    private Long issueId;

    @Schema(description = "订单号")
    private String issueNo;

    @Schema(description = "产品编码")
    private String productCode;

    @Schema(description = "冲突内容")
    private String conflictContent;

    @Schema(description = "是否已处理")
    private Boolean resolved;

    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
