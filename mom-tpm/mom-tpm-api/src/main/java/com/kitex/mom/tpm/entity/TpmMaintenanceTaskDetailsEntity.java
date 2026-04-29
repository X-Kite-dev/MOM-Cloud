package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 保养任务详情表
 *
 * @author kitex
 * @date 2025-02-06 15:19:44
 */
@Data
@TableName("tpm_maintenance_task_details")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "保养任务详情表")
public class TpmMaintenanceTaskDetailsEntity extends Model<TpmMaintenanceTaskDetailsEntity> {


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
     * 保养项编码
     */
    @Schema(description = "保养项编码")
    private String itemCode;

    /**
     * 保养项名称
     */
    @Schema(description = "保养项名称")
    private String itemName;

    /**
     * 检验方法
     */
    @Schema(description = "检验方法")
    private String checkMethod;

    /**
     * 检验工具
     */
    @Schema(description = "检验工具")
    private String checkTool;

    /**
     * 判断类型：0 直接确认 1 填写值
     */
    @Schema(description = "判断类型：0 直接确认 1 填写值")
    private String judgeType;

    /**
     * 填写结果
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "填写结果")
    private String result;

    /**
     * 标准上限
     */
    @Schema(description = "标准上限")
    private BigDecimal standardMax;

    /**
     * 标准下限
     */
    @Schema(description = "标准下限")
    private BigDecimal standardMin;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;
}