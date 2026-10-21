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
 * 一次真实feed负向OLD/NEW及全部选定原份额合计。
 */
@Data
@TenantTable
@TableName("mes_production_feed_restore_line")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "一次真实feed负向OLD/NEW及全部选定原份额合计")
public class MesProductionFeedRestoreLineEntity extends BaseEntity<MesProductionFeedRestoreLineEntity> {
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
     * 该feed在原batch唯一总耗用根主键。
     */
    @Schema(description = "该feed在原batch唯一总耗用根主键")
    private Long consumptionId;

    /**
     * 实际原投料主键，不能换批或换来源恢复。
     */
    @Schema(description = "实际原投料主键，不能换批或换来源恢复")
    private Long feedId;

    /**
     * 数据库绑定本次唯一真实负向凭证，应用不得注入。
     */
    @Schema(description = "数据库绑定本次唯一真实负向凭证，应用不得注入")
    @TableField(insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private Long feedMutationId;

    /**
     * 原投料实际物料编码。
     */
    @Schema(description = "原投料实际物料编码")
    private String matCode;

    /**
     * 原投料实际单位，不查当前MDM换算。
     */
    @Schema(description = "原投料实际单位，不查当前MDM换算")
    private String matUnit;

    /**
     * 原投料总量，恢复不改变总量。
     */
    @Schema(description = "原投料总量，恢复不改变总量")
    private BigDecimal feedNum;

    /**
     * 本次全部选定原share在该feed的实际数量合计。
     */
    @Schema(description = "本次全部选定原share在该feed的实际数量合计")
    private BigDecimal restoredNum;

    /**
     * 本次真实CAS前当前已用量，保留后来合法耗用。
     */
    @Schema(description = "本次真实CAS前当前已用量，保留后来合法耗用")
    private BigDecimal usedBeforeNum;

    /**
     * 本次真实CAS后已用量，不覆盖原首次耗用前值。
     */
    @Schema(description = "本次真实CAS后已用量，不覆盖原首次耗用前值")
    private BigDecimal usedAfterNum;

    /**
     * 原总耗用根固定字段完整事实原文。
     */
    @Schema(description = "原总耗用根固定字段完整事实原文")
    private String originalConsumptionSnapshot;

    /**
     * 实际工厂由已认证上下文和真实原账共同确定。
     */
    @Schema(description = "实际工厂，不由客户端声明授予权限")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
