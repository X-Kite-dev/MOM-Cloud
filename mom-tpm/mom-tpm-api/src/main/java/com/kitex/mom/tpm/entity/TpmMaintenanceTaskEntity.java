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
 * 保养任务表
 *
 * @author kitex
 * @date 2025-02-06 15:17:39
 */
@Data
@TenantTable
@TableName("tpm_maintenance_task")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "保养任务表")
public class TpmMaintenanceTaskEntity extends BaseEntity<TpmMaintenanceTaskEntity> {


    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "id")
    private Long id;

    /**
     * 任务编码
     */
    @Schema(description = "任务编码")
    private String taskCode;

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
     * 处理时长单位：分钟、小时、天
     */
    @Schema(description = "处理时长单位：分钟、小时、天")
    private String handleTimeUnit;

    /**
     * 处理时长时间值
     */
    @Schema(description = "处理时长时间值")
    private Integer handleTime;

    /**
     * 保养时间
     */
    @Schema(description = "保养时间")
    private LocalDateTime maintenanceTime;

    /** 首次实际处理保养项目的时间，不再使用任务创建时间冒充实际停机开始。 */
    @Schema(description = "保养实际开始时间")
    private LocalDateTime maintenanceStartTime;

    /** 基于标准时长或首次投递前人工校正的计划恢复时刻。 */
    @Schema(description = "预计恢复时间")
    private LocalDateTime expectedRecoveryTime;

    @Schema(description = "恢复预测依据")
    private String recoveryForecastReason;

    /**
     * 预计保养时间
     */
    @TableField(exist = false)
    @Schema(description = "预计保养时间")
    private LocalDateTime planMaintenanceTime;

    /**
     * 任务状态：0 未处理 1 处理中 2 已处理 3 超时已处理
     */
    @Schema(description = "任务状态：0 未处理 1 处理中 2 已处理 3 超时已处理")
    private String state;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;


    @TableField(exist = false)
    @Schema(description = "子表列表")
    private List<TpmMaintenanceTaskDetailsEntity> detailsList;
}
