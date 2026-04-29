package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 保养计划明细表
 *
 * @author kitex
 * @date 2025-02-06 15:10:29
 */
@Data
@TableName("tpm_maintenance_plan_details")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "保养计划明细表")
public class TpmMaintenancePlanDetailsEntity extends Model<TpmMaintenancePlanDetailsEntity> {


    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "id")
    private Long id;

    /**
     * 计划id
     */
    @Schema(description = "计划id")
    private Long planId;

    /**
     * 保养项id
     */
    @Schema(description = "保养项id")
    private Long itemId;

    /**
     * 保养项编码
     */
    @Schema(description = "保养项编码")
    private String itemCode;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    @TableField(exist = false)
    @Schema(description = "保养项名称")
    private String itemName;

    @TableField(exist = false)
    @Schema(description = "检验方法")
    private String checkMethod;

    @TableField(exist = false)
    @Schema(description = "检验工具")
    private String checkTool;

    @TableField(exist = false)
    @Schema(description = "判断类型")
    private String judgeType;

    @TableField(exist = false)
    @Schema(description = "标准上限")
    private BigDecimal standardMax;

    @TableField(exist = false)
    @Schema(description = "标准下限")
    private BigDecimal standardMin;

}