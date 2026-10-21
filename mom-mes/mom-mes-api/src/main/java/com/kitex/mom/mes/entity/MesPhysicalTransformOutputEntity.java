package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/** 正式转换输出事实；仅GOOD生成可用库存，SCRAP/MISSING保留原事实但不伪造库存。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_physical_transform_output")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "实物转换输出事实")
public class MesPhysicalTransformOutputEntity extends BaseEntity<MesPhysicalTransformOutputEntity> {
    private String factoryCode;
    private Long executionId;
    private String outputKey;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String identifier;
    private String objectType;
    private String disposition;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String positionCode;
    private BigDecimal quantity;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long resultStockId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long inFlowId;
}
