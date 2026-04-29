package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 点/巡检计划
 *
 * @author kitex
 * @date 2025-02-18 17:44:03
 */
@Data
@TableName("tpm_check_plan")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "点/巡检计划")
public class TpmCheckPlanEntity extends BaseEntity<TpmCheckPlanEntity> {


    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "id")
    private Long id;

    /**
     * 计划名称
     */
    @Schema(description = "计划名称")
    private String name;

    /**
     * 类型  spot 点检 patrol巡检
     */
    @Schema(description = "类型  spot 点检 patrol巡检")
    private String type;

    /**
     * 计划开始时间
     */
    @JsonFormat(timezone = "GMT+8", pattern = "yyy-MM-dd HH:mm:ss")
    @Schema(description = "计划开始时间")
    private LocalDateTime planStart;

    /**
     * 计划结束时间
     */
    @JsonFormat(timezone = "GMT+8", pattern = "yyy-MM-dd HH:mm:ss")
    @Schema(description = "计划结束时间")
    private LocalDateTime planEnd;

    @JsonFormat(timezone = "GMT+8", pattern = "yyy-MM-dd HH:mm:ss")
    @Schema(description = "上次执行时间")
    private LocalDateTime lastTime;

    /**
     * 周期时间单位（分钟、小时、天）
     */
    @Schema(description = "周期时间单位（分钟、小时、天）")
    private String circleTimeUnit;

    /**
     * 周期时间值
     */
    @Schema(description = "周期时间值")
    private Integer circleTime;

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
     * 设备分类
     */
    @TableField(exist = false)
    @Schema(description = "设备分类")
    private String deviceClass;

    /**
     * 计划执行状态 0 执行中 1 已暂停
     */
    @Schema(description = "计划执行状态 0 执行中 1 已暂停")
    private String executeState;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}