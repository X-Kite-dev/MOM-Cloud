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
 * 保养计划表
 *
 * @author kitex
 * @date 2025-02-06 15:07:31
 */
@Data
@TenantTable
@TableName("tpm_maintenance_plan")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "保养计划表")
public class TpmMaintenancePlanEntity extends BaseEntity<TpmMaintenancePlanEntity> {


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
     * 计划类型 0 单次保养 1 循环保养
     */
    @Schema(description = "计划类型 0 单次保养 1 循环保养")
    private String type;

    /**
     * 计划开始时间
     */
    @Schema(description = "计划开始时间")
    private LocalDateTime startTime;

    /**
     * 上次执行时间
     */
    @Schema(description = "上次执行时间")
    private LocalDateTime lastTime;


    /**
     * 周期单位 minute 分钟 hour 小时 day 天
     */
    @Schema(description = "周期单位 minute 分钟 hour 小时 day 天")
    private String circleTimeUnit;

    /**
     * 周期值
     */
    @Schema(description = "周期值")
    private Integer circleTime;

    /**
     * 提前通知时间单位 minute 分钟 hour 小时 day 天
     */
    @Schema(description = "提前通知时间单位 minute 分钟 hour 小时 day 天")
    private String noticeTimeUnit;

    /**
     * 提前通知时间值
     */
    @Schema(description = "提前通知时间值")
    private Integer noticeTime;

    /**
     * 通知方式 0 业务系统 1 钉钉 2 全部
     */
    @Schema(description = "通知方式 0 业务系统 1 钉钉 2 全部")
    private String noticeType;

    /**
     * 通知标识
     */
    @Schema(description = "通知标识 0 未通知  1 已通知")
    private String noticeFlag;

    /**
     * 处理时长单位 minute 分钟 hour 小时 day 天
     */
    @Schema(description = "处理时长单位 minute 分钟 hour 小时 day 天")
    private String handleTimeUnit;

    /**
     * 处理时长时间值
     */
    @Schema(description = "处理时长时间值")
    private Integer handleTime;

    /**
     * 计划执行状态 0 执行中 1 已暂停
     */
    @Schema(description = "计划执行状态 0 执行中 1 已暂停")
    private String executeState;

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
    @Schema(description = "设备分类")
    private String deviceClass;

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

    @TableField(exist = false)
    @Schema(description = "计划保养项明细列表")
    private List<TpmMaintenancePlanDetailsEntity> detailsList;
}
