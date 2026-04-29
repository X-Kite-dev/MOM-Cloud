package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 点/巡检项目
 *
 * @author kitex
 * @date 2025-02-18 17:40:04
 */
@Data
@TenantTable
@TableName("tpm_check_item")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "点/巡检项目")
public class TpmCheckItemEntity extends BaseEntity<TpmCheckItemEntity> {


    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "id")
    private Long id;

    /**
     * 编码
     */
    @Schema(description = "编码")
    private String code;

    /**
     * 名称
     */
    @Schema(description = "名称")
    private String name;

    /**
     * 类型  spot 点检 patrol巡检
     */
    @Schema(description = "类型  spot 点检 patrol巡检")
    private String type;

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

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}