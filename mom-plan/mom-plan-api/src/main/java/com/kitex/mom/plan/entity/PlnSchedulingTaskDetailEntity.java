package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
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

    /** 排程开始时已由MDM精确核验的工程版本ID。 */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @Schema(description = "排程工程版本ID快照")
    private Long engineeringReleaseId;

    /** 与releaseId共同确定唯一工程原文的SHA256。 */
    @Schema(description = "排程工程版本内容指纹")
    private String engineeringContentHash;

    @Schema(description = "排程工程版本号快照")
    private Integer engineeringVersionNo;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @Schema(description = "排程精确路线ID快照")
    private Long engineeringRouteId;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @Schema(description = "排程精确BOM ID快照")
    private Long engineeringBomId;

    @Schema(description = "排程精确BOM版本快照")
    private String engineeringBomVersion;

    @Schema(description = "物料约束状态：READY、WAITING_SUPPLY及各类硬阻断状态")
    private String materialConstraintStatus;

    @Schema(description = "所有下级物料预计齐套后的最早可开工日期")
    private LocalDate materialAvailableDate;

    @Schema(description = "所有下级物料逐层汇合后的分钟级最早可开工时刻")
    private LocalDateTime materialAvailableTime;

    @Schema(description = "物料约束是否硬阻断正式锁定")
    private Boolean materialHardBlocked;

    @Schema(description = "物料约束业务摘要")
    private String materialConstraintSummary;

    @Schema(description = "MRP物料约束明细JSON快照")
    private String materialConstraintSnapshot;

    @Schema(description = "逐工序物料汇合约束JSON快照")
    private String operationMaterialConstraintSnapshot;

    @Schema(description = "本次MRP BOM路径证据综合指纹")
    private String materialLineageFingerprint;

    @Schema(description = "最近一次物料约束复核时间")
    private LocalDateTime materialConstraintCheckedTime;

    @Schema(description = "工序级有限产能排程状态")
    private String operationScheduleStatus;

    @Schema(description = "工序级排程是否硬阻断")
    private Boolean operationHardBlocked;

    @Schema(description = "工序级排程摘要")
    private String operationScheduleSummary;

    @Schema(description = "排产工序数量")
    private Integer operationCount;

    @Schema(description = "首道工序计划开始时间")
    private LocalDateTime operationStartTime;

    @Schema(description = "末道工序计划结束时间")
    private LocalDateTime operationEndTime;

    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
