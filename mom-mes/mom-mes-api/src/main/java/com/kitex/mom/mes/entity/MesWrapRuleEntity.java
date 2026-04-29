package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 箱码规则管理 实体类
 * </p>
 *
 * @author kitex
 * @since 2025-03-25
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Accessors(chain = true)
@Schema(description = "箱码规则管理")
@TableName("mes_wrap_rule")
public class MesWrapRuleEntity extends BaseEntity<MesWrapRuleEntity> {

    /**
     * 箱码规则编号
     */
    @Schema(description = "箱码规则编号")
    private String wrapCode;

    /**
     * 箱码规则名称
     */
    @Schema(description = "箱码规则名称")
    private String wrapName;

    /**
     * 容量
     */
    @Schema(description = "容量")
    private Integer capacity;

    /**
     * 状态 true启用 false停用
     */
    @Schema(description = "状态 true启用 false停用")
    private Boolean status;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}
