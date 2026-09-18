package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 点/巡检任务
 *
 * @author kitex
 * @date 2025-02-18 17:49:05
 */
@Data
@TenantTable
@TableName("tpm_check_task")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "点/巡检任务")
public class TpmCheckTaskEntity extends BaseEntity<TpmCheckTaskEntity> {


    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "id")
    private Long id;

    /**
     * 任务号
     */
    @Schema(description = "任务号")
    private String taskCode;

    /**
     * 类型  spot 点检 patrol巡检
     */
    @Schema(description = "类型  spot 点检 patrol巡检")
    private String type;

    /**
     * 来源  设备开机 周期计划
     */
    @Schema(description = "来源  设备开机 周期计划")
    private String source;


    /**
     * 计划id
     */
    @Schema(description = "计划id")
    private Long planId;

    /**
     * 计划名称
     */
    @Schema(description = "计划名称")
    private String planName;

    /**
     * 设备编码
     */
    @Schema(description = "设备编码")
    private String deviceCode;

    /**
     * 设备名称
     */
    @Schema(description = "设备名称")
    private String deviceName;

    /**
     * 处理人编码
     */
    @Schema(description = "处理人编码")
    private String handlePersonCode;

    /**
     * 处理人名称
     */
    @Schema(description = "处理人名称")
    private String handlePersonName;

    /**
     * 点检时间
     */
    @Schema(description = "点检时间")
    private LocalDateTime handleTime;

    /**
     * 状态 0 未开始 1 进行中 2 已完成
     */
    @Schema(description = "状态 0 未开始 1 进行中 2 已完成")
    private String state;


    /**
     * 判断结果 0 通过  1 不通过
     */
    @Schema(description = "判断结果 0 通过  1 不通过")
    private String judgeResult;

    @Schema(description = "生产封锁状态：NONE无封锁、BLOCKED封锁中、RELEASED已解除")
    private String blockStatus;

    @Schema(description = "本次不合格的最高处置级别：WARN、BLOCK、REPAIR")
    private String failureAction;

    @Schema(description = "生产封锁原因")
    private String blockReason;

    @Schema(description = "生产封锁发生时间")
    private LocalDateTime blockTime;

    @Schema(description = "预计恢复时间；允许暂缺，暂缺时不触发智能重排")
    private LocalDateTime expectedRecoveryTime;

    @Schema(description = "预计恢复时间预测依据")
    private String recoveryForecastReason;

    @Schema(description = "封锁操作人编码")
    private String blockOperatorCode;

    @Schema(description = "封锁操作人名称")
    private String blockOperatorName;

    @Schema(description = "封锁前设备状态，用于安全恢复")
    private String deviceStateBeforeBlock;

    @Schema(description = "封锁前设备可用状态，用于安全恢复")
    private String deviceUsableBeforeBlock;

    @Schema(description = "解除请求幂等号")
    private String releaseRequestId;

    @Schema(description = "解除原因")
    private String releaseReason;

    @Schema(description = "解除证据（复检记录、安全确认等）")
    private String releaseEvidence;

    @Schema(description = "REPAIR处置关联的已完成维修任务编码")
    private String relatedRepairTaskCode;

    @Schema(description = "解除人编码")
    private String releaseOperatorCode;

    @Schema(description = "解除人名称")
    private String releaseOperatorName;

    @Schema(description = "解除时间")
    private LocalDateTime releasedTime;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    @TableField(exist = false)
    @Schema(description = "任务明细")
    private List<TpmCheckTaskDetailsEntity> detailsList;
}
