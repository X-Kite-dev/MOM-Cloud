package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 提醒配置表
 *
 * @author kitex
 * @date 2025-02-06 15:39:14
 */
@Data
@TableName("tpm_repair_remind")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "提醒配置表")
public class TpmRepairRemindEntity extends BaseEntity<TpmRepairRemindEntity> {


    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "id")
    private Long id;

    /**
     * 提醒编号
     */
    @Schema(description = "提醒编号")
    private String remindCode;

    /**
     * 故障类型
     */
    @Schema(description = "故障类型")
    private Long faultClassId;

    /**
     * 故障类型名称
     */
    @TableField(exist = false)
    @Schema(description = "故障类型名称")
    private String faultClassName;

    /**
     * 设备编码集合
     */
    @Schema(description = "设备编码集合")
    private String deviceCodes;

    /**
     * 设备名称集合
     */
    @Schema(description = "设备名称集合")
    private String deviceNames;

    /**
     * 班次编码集合
     */
    @Schema(description = "班次编码集合")
    private String shiftCodes;

    /**
     * 班次名称集合
     */
    @Schema(description = "班次名称集合")
    private String shiftNames;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 处理人
     */
    @TableField(exist = false)
    @Schema(description = "处理人")
    private String handlePerson;

    /**
     * 配置人员明细
     */
    @TableField(exist = false)
    @Schema(description = "配置人员明细")
    private List<TpmRepairRemindDetailsEntity> detailsList;
}

