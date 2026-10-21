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
 * 永久唯一选定产出；零需求也必须一次认领。
 */
@Data
@TenantTable
@TableName("mes_production_feed_reversal_output")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "永久唯一选定产出；零需求也必须一次认领")
public class MesProductionFeedReversalOutputEntity extends BaseEntity<MesProductionFeedReversalOutputEntity> {
    /**
     * 本次独立纠正主账主键。
     */
    @Schema(description = "本次独立纠正主账主键")
    private Long reversalId;

    /**
     * 原总投料主账主键。
     */
    @Schema(description = "原总投料主账主键")
    private Long batchId;

    /**
     * 永久唯一恢复的原typed产出主键。
     */
    @Schema(description = "永久唯一恢复的原typed产出主键")
    private Long outputId;

    /**
     * 原产出固定字段完整事实，不用当前报告重建。
     */
    @Schema(description = "原产出固定字段完整事实，不用当前报告重建")
    private String originalOutputSnapshot;

    /**
     * 实际工厂由已认证上下文和真实原账共同确定。
     */
    @Schema(description = "实际工厂，不由客户端声明授予权限")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
