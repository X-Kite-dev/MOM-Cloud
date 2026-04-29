package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 保养项目表
 *
 * @author kitex
 * @date 2025-02-06 15:04:12
 */
@Data
@TenantTable
@TableName("tpm_maintenance_item")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "保养项目表")
public class TpmMaintenanceItemEntity extends BaseEntity<TpmMaintenanceItemEntity> {


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
     * 判断类型0直接确认 1填写值
     */
    @Schema(description = "判断类型0直接确认 1填写值")
    private String judgeType;

    /**
     * 标准上限
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "标准上限")
    private BigDecimal standardMax;

    /**
     * 标准下限
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "标准下限")
    private BigDecimal standardMin;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}