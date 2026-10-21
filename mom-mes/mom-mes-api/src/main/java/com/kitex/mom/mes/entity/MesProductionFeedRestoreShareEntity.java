package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 一条原产出份额的全额恢复认领；不修改旧share。
 */
@Data
@TenantTable
@TableName("mes_production_feed_restore_share")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "一条原产出份额的全额恢复认领；不修改旧share")
public class MesProductionFeedRestoreShareEntity extends BaseEntity<MesProductionFeedRestoreShareEntity> {
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
     * 永久唯一原share主键，不能另一原号重复恢复。
     */
    @Schema(description = "永久唯一原share主键，不能另一原号重复恢复")
    private Long originalShareId;

    /**
     * 原typed产出主键，须是本次明确选中的成员。
     */
    @Schema(description = "原typed产出主键，须是本次明确选中的成员")
    private Long outputId;

    /**
     * 原总耗用根主键，实际来源不从物料猜。
     */
    @Schema(description = "原总耗用根主键，实际来源不从物料猜")
    private Long consumptionId;

    /**
     * 本次逐feed一次真实负向恢复根主键。
     */
    @Schema(description = "本次逐feed一次真实负向恢复根主键")
    private Long restoreLineId;

    /**
     * 原share实际单位全额数量，不按新比例计算。
     */
    @Schema(description = "原share实际单位全额数量，不按新比例计算")
    private BigDecimal consumeNum;

    /**
     * 原share需求单位全额数量。
     */
    @Schema(description = "原share需求单位全额数量")
    private BigDecimal requirementNum;

    /**
     * 原share固定字段完整事实原文。
     */
    @Schema(description = "原share固定字段完整事实原文")
    private String originalShareSnapshot;

    /**
     * 实际工厂由已认证上下文和真实原账共同确定。
     */
    @Schema(description = "实际工厂，不由客户端声明授予权限")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
