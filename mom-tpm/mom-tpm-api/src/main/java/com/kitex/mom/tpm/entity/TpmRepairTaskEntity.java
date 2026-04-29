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
 * 维修任务表
 *
 * @author kitex
 * @date 2025-02-06 16:06:41
 */
@Data
@TenantTable
@TableName("tpm_repair_task")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "维修任务表")
public class TpmRepairTaskEntity extends BaseEntity<TpmRepairTaskEntity> {


    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "id")
    private Long id;

    /**
     * 维修任务编码
     */
    @Schema(description = "维修任务编码")
    private String taskCode;

    /**
     * 维修任务类型  internal 内部维修   outsourcing 委外维修
     */
    @Schema(description = "维修任务编码")
    private String type;

    /**
     * 故障分类id
     */
    @Schema(description = "故障分类id")
    private Long faultClassId;

    /**
     * 故障分类名称
     */
    @Schema(description = "故障分类名称")
    private String faultClassName;

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
     * 班次编码
     */
    @Schema(description = "班次编码")
    private String shiftCode;

    /**
     * 班次名称
     */
    @Schema(description = "班次名称")
    private String shiftName;


    /**
     * 设备位置
     */
    @Schema(description = "设备位置")
    private String deviceLocation;

    /**
     * 报修人
     */
    @Schema(description = "报修人")
    private String submitBy;

    /**
     * 报修人名称
     */
    @Schema(description = "报修人名称")
    private String submitByName;

    /**
     * 报修时间
     */
    @Schema(description = "报修时间")
    private LocalDateTime submitTime;

    /**
     * 故障原因
     */
    @Schema(description = "故障原因")
    private String faultReason;

    /**
     * 故障描述
     */
    @Schema(description = "故障描述")
    private String faultDesc;

    /**
     * 维修方法
     */
    @Schema(description = "维修方法")
    private String repairMethod;

    /**
     * 维修结果
     */
    @Schema(description = "维修结果")
    private String repairResult;

    /**
     * 维修人
     */
    @Schema(description = "维修人")
    private String repairBy;

    /**
     * 维修人名称
     */
    @Schema(description = "维修人名称")
    private String repairByName;

    /**
     * 维修时间
     */
    @Schema(description = "维修时间")
    private LocalDateTime repairTime;

    /**
     * 状态 0未完成 1已完成
     */
    @Schema(description = "状态 0未完成 1已完成")
    private String state;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;


    @TableField(exist = false)
    @Schema(description = "维修任务详情")
    private List<TpmRepairTaskDetailsEntity> detailsList;

    @TableField(exist = false)
    @Schema(description = "是否提醒")
    private Boolean remindSign;
}