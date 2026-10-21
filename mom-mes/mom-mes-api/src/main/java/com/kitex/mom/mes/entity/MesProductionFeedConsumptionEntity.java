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
 * 一次实际feed增加量的唯一消费根；GOOD/NG份额只引用此根，不复制OLD/NEW。
 */
@Data
@TenantTable
@TableName("mes_production_feed_consumption")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "一次实际feed增加量的唯一消费根；GOOD/NG份额只引用此根，不复制OLD/NEW")
public class MesProductionFeedConsumptionEntity extends BaseEntity<MesProductionFeedConsumptionEntity> {
    /**
     * 所属独立生产总投料账主键。
     */
    @Schema(description = "所属独立生产总投料账主键")
    private Long batchId;

    /**
     * 真实原投料主键。
     */
    @Schema(description = "真实原投料主键")
    private Long feedId;

    /**
     * 数据库自动绑定的本事务唯一真实OLD/NEW凭证，应用不得注入。
     */
    @Schema(description = "数据库自动绑定的本事务唯一真实OLD/NEW凭证，应用不得注入")
    @TableField(insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private Long feedMutationId;

    /**
     * 原工序需求物料编码。
     */
    @Schema(description = "原工序需求物料编码")
    private String requirementMatCode;

    /**
     * 原工序需求单位。
     */
    @Schema(description = "原工序需求单位")
    private String requirementUnit;

    /**
     * 实际投料物料编码，可能是获准替代料。
     */
    @Schema(description = "实际投料物料编码，可能是获准替代料")
    private String matCode;

    /**
     * 实际投料原单位，不用产出单位代替。
     */
    @Schema(description = "实际投料原单位，不用产出单位代替")
    private String matUnit;

    /**
     * 本次该投料真实耗用总量，不再次执行库存OUT。
     */
    @Schema(description = "本次该投料真实耗用总量，不再次执行库存OUT")
    private BigDecimal consumeNum;

    /**
     * 本次满足的原需求当量。
     */
    @Schema(description = "本次满足的原需求当量")
    private BigDecimal requirementNum;

    /**
     * 原实际耗量与需求当量的精确比例。
     */
    @Schema(description = "原实际耗量与需求当量的精确比例")
    private BigDecimal substituteRatio;

    /**
     * 是否按原BOM明确使用替代料。
     */
    @Schema(description = "是否按原BOM明确使用替代料")
    private Boolean substituteFlag;

    /**
     * 原真实投料总量。
     */
    @Schema(description = "原真实投料总量")
    private BigDecimal feedNum;

    /**
     * 本次真实CAS前已用量。
     */
    @Schema(description = "本次真实CAS前已用量")
    private BigDecimal usedBeforeNum;

    /**
     * 本次真实CAS后已用量。
     */
    @Schema(description = "本次真实CAS后已用量")
    private BigDecimal usedAfterNum;

    /**
     * 本次实际实名计量观察的物料主键。
     */
    @Schema(description = "本次实际实名计量观察的物料主键")
    private Long materialId;

    /**
     * 本次计量观察原更新时间文本，历史不查当前版本补造。
     */
    @Schema(description = "本次计量观察原更新时间文本，历史不查当前版本补造")
    private String materialUpdatedAt;

    /**
     * 真实工厂，服务端按已认证上下文确定。
     */
    @Schema(description = "真实工厂，不能由页面声明授权")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
