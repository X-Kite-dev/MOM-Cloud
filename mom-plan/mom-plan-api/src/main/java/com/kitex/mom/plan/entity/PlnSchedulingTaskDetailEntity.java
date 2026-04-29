package com.kitex.mom.plan.entity;

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
@TableName("pln_scheduling_task_detail")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "排产任务明细快照")
public class PlnSchedulingTaskDetailEntity extends BaseEntity<PlnSchedulingTaskDetailEntity> {

    @Schema(description = "任务ID")
    private Long taskId;

    @Schema(description = "计划ID")
    private Long issueId;

    @Schema(description = "计划号")
    private String issueNo;

    @Schema(description = "客户编码")
    private String customerCode;

    @Schema(description = "客户名称")
    private String customerName;

    @Schema(description = "产品编码")
    private String productCode;

    @Schema(description = "产品名称")
    private String productName;

    @Schema(description = "规格型号")
    private String specificationModel;

    @Schema(description = "数量")
    private BigDecimal num;

    @Schema(description = "下达时间")
    private LocalDateTime issueTime;

    @Schema(description = "需求时间")
    private LocalDate demandTime;

    @Schema(description = "分配日期")
    private LocalDate scheduledDate;

    private LocalDate scheduledEndDate;

    @Schema(description = "是否锁定")
    private Boolean lockStatus;

    @Schema(description = "锁定任务ID")
    private Long lockTaskId;

    @Schema(description = "锁定时间")
    private LocalDateTime lockTime;

    @Schema(description = "状态")
    private String status;

    @Schema(description = "来源类型")
    private String type;

    @Schema(description = "计划类型")
    private String planType;

    @Schema(description = "规则命中信息(JSON)")
    private String matchedRules;

    @Schema(description = "评分拆解(JSON)")
    private String scoreBreakdown;

    @Schema(description = "工艺路线编码（快照，用于冲突检测）")
    private String processRouteCode;

    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
