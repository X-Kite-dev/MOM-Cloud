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
 * 真实耗用根分配到逐产出的永久份额；用于独立恢复而非总量比例舍入。
 */
@Data
@TenantTable
@TableName("mes_production_feed_share")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "真实耗用根分配到逐产出的永久份额；用于独立恢复而非总量比例舍入")
public class MesProductionFeedShareEntity extends BaseEntity<MesProductionFeedShareEntity> {
    /**
     * 所属独立生产总投料账主键。
     */
    @Schema(description = "所属独立生产总投料账主键")
    private Long batchId;

    /**
     * 明确GOOD或NG产出行主键。
     */
    @Schema(description = "明确GOOD或NG产出行主键")
    private Long outputId;

    /**
     * 唯一实际耗用根主键，不重复认领同一投料变动。
     */
    @Schema(description = "唯一实际耗用根主键，不重复认领同一投料变动")
    private Long consumptionId;

    /**
     * 该份产出认领的实际投料原单位耗量。
     */
    @Schema(description = "该份产出认领的实际投料原单位耗量")
    private BigDecimal consumeNum;

    /**
     * 该份产出认领的原需求单位当量。
     */
    @Schema(description = "该份产出认领的原需求单位当量")
    private BigDecimal requirementNum;

    /**
     * 真实工厂，服务端按已认证上下文确定。
     */
    @Schema(description = "真实工厂，不能由页面声明授权")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
