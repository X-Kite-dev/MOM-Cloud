package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;

/**
 * 排产任务
 */
@Data
@TenantTable
@TableName("pln_scheduling_task")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "排产任务")
public class PlnSchedulingTaskEntity extends BaseEntity<PlnSchedulingTaskEntity> {

    @Schema(description = "任务编号")
    private String taskNo;

    @Schema(description = "触发方式: AUTO/MANUAL")
    private String triggerType;

    @Schema(description = "计划周期: DAY/WEEK/MONTH")
    private String cycleType;

    @Schema(description = "运行模式: simulate/official")
    private String mode;

    @Schema(description = "任务状态: PENDING/RUNNING/SIMULATED/WAIT_CONFIRM/SUCCESS/FAILED/PARTIAL/SUPERSEDED/CANCELLED")
    private String taskStatus;

    @Schema(description = "计划日期")
    private LocalDate scheduleDate;

    @Schema(description = "命中规则编码")
    private String ruleCode;

    @Schema(description = "规则集版本")
    private Integer ruleVersion;

    @Schema(description = "计划数量")
    private Integer planCount;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "所属多候选排产情景ID；为空表示普通单方案任务")
    private Long scenarioId;
    private String candidateCode;
    private String candidateName;
    @Schema(description = "DELIVERY_FIRST、BALANCED_GLOBAL或CHANGEOVER_FIRST")
    private String sequenceStrategy;
    private Integer crossPriorityWindowDays;
    private String objectiveWeightsSnapshot;
    private String candidateMetricsSnapshot;
    private BigDecimal candidateScore;
    private Integer candidateRank;
    private Boolean candidateFeasible;
    private Boolean selectedCandidate;
    private LocalDateTime evaluatedTime;

    @Schema(description = "结果归档标签，不代表释放资源或撤回生产")
    private Boolean resultArchived;
    @Schema(description = "任务生命周期操作版本；历史基线为0")
    private Integer lifecycleVersion;
    @Schema(description = "受控运行请求ID；原子绑定唯一求解结果，不覆盖原失败尝试")
    private Long executionId;

    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
