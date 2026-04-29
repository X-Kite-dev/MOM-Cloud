package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 安灯异常处理配置 实体类
 *
 * @author kitex
 * @since 2025-05-18 16:20:02
 */
@Data
@TenantTable
@TableName("mes_andon_handle_config")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "安灯异常处理配置")
public class MesAndonHandleConfigEntity extends BaseEntity<MesAndonHandleConfigEntity> {


    /**
     * 流程编码
     */
    @Schema(description = "流程编码")
    private String code;

    /**
     * 异常类型编码
     */
    @Schema(description = "异常类型编码")
    private String typeCode;

    /**
     * 异常类型名称
     */
    @Schema(description = "异常类型名称")
    private String typeName;

    /**
     * 车间编码
     */
    @Schema(description = "车间编码")
    private String workshopCode;

    /**
     * 车间名称
     */
    @Schema(description = "车间名称")
    private String workshopName;

    /**
     * 产线编号
     */
    @Schema(description = "产线编号")
    private String lineCode;

    /**
     * 产线名称
     */
    @Schema(description = "产线名称")
    private String lineName;

    /**
     * 班次编号
     */
    @Schema(description = "班次编号")
    private String shiftCode;

    /**
     * 班次名称
     */
    @Schema(description = "班次名称")
    private String shiftName;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
