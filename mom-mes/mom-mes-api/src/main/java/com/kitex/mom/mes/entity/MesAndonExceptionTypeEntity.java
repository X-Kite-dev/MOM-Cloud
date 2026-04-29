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
 * <p>
 * 安灯异常类型配置 实体类
 * </p>
 *
 * @author kitex
 * @since 2025-05-18 15:27:53
 */
@Data
@TenantTable
@TableName("mes_andon_exception_type")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "安灯异常类型配置")
public class MesAndonExceptionTypeEntity extends BaseEntity<MesAndonExceptionTypeEntity> {

    /**
     * 异常编码
     */
    @Schema(description = "异常编码")
    private String code;

    /**
     * 异常名称
     */
    @Schema(description = "异常名称")
    private String name;

    /**
     * 工作台展示
     */
    @Schema(description = "工作台展示")
    private String benchShow;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;


    /**
     * 创建人姓名
     */
    @Schema(description = "创建人姓名")
    private String createByName;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
