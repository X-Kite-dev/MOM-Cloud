package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@TenantTable
@TableName("mes_change_point_level_config")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "变化点等级配置")
public class MesChangePointLevelConfigEntity extends BaseEntity<MesChangePointLevelConfigEntity> {

    @Schema(description = "变化点编码")
    private String configCode;

    @Schema(description = "变化点类型编码")
    private String code;

    @Schema(description = "变化点类型")
    private String type;

    @Schema(description = "变化点内容")
    private String content;

    @Schema(description = "等级")
    private String level;

    @Schema(description = "验证角色")
    private String verifyRole;

    @Schema(description = "上限")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "0.00")
    private BigDecimal upperLimit;

    @Schema(description = "下限")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "0.00")
    private BigDecimal lowerLimit;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
