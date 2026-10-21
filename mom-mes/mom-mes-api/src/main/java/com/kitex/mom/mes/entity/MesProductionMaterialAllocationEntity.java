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
 * 原耗料根到实际NG来源的分配；当前一根一来源，不冒充GOOD消耗/恢复账。
 */
@Data
@TenantTable
@TableName("mes_production_material_allocation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "实际不良投料耗用分配")
public class MesProductionMaterialAllocationEntity extends BaseEntity<MesProductionMaterialAllocationEntity> {
    /**
     * 独立生产物料operation及原真实NG来源。
     */
    private Long operationId;
    private Long sourceUnqualifiedReportId;
    /**
     * 实际投料行，不以物料/批次猜首条来源。
     */
    private Long feedId;
    /**
     * 冻结需求物料/单位与实际物料/原投料单位。
     */
    private String requirementMatCode;
    private String requirementUnit;
    private String matCode;
    private String matUnit;
    /**
     * 实际耗量、满足需求当量、实际/需求替代比例；全部精确小数。
     */
    private BigDecimal consumeNum;
    private BigDecimal requirementNum;
    private BigDecimal substituteRatio;
    private Boolean substituteFlag;
    /**
     * 原投料总量与本次耗用前后值，NULL used_num按原未耗用明确处理为0。
     */
    private BigDecimal feedNum;
    private BigDecimal usedBeforeNum;
    private BigDecimal usedAfterNum;
    /**
     * 当次启用实际物料的主键与精确更新时间文本；历史空时间仍为空。
     */
    private Long materialId;
    private String materialUpdatedAt;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
