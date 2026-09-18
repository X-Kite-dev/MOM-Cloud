package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 点/巡检任务详情
 *
 * @author kitex
 * @date 2025-02-18 17:51:20
 */
@Data
@TableName("tpm_check_task_details")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "点/巡检任务详情")
public class TpmCheckTaskDetailsEntity extends Model<TpmCheckTaskDetailsEntity> {


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
     * 点检项编码
     */
    @Schema(description = "点检项编码")
    private String itemCode;

    /**
     * 点检项名称
     */
    @Schema(description = "点检项名称")
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
     * 检验标准
     */
    @Schema(description = "检验标准")
    private String checkStandard;

    /**
     * 判断类型 0 直接确认 1 填写值
     */
    @Schema(description = "判断类型 0 直接确认 1 填写值")
    private String judgeType;

    /**
     * 填写结果
     */
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

    @Schema(description = "任务创建时冻结的不合格处置：WARN、BLOCK、REPAIR")
    private String failureAction;

    @Schema(description = "任务创建时冻结的默认预计恢复分钟数")
    private Integer defaultRecoveryMinutes;

    @Schema(description = "任务创建时冻结的不合格处置指引")
    private String failureGuidance;

    /**
     * 判断结果 1 通过  0 不通过
     */
    @Schema(description = "判断结果 1 通过  0 不通过")
    private String judgeResult;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;
}
