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
 * NG耗料全额恢复的逐feed原分配；原单位及当前前后量永久保留。
 */
@Data
@TenantTable
@TableName("mes_production_material_restore")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "NG耗料全额恢复的逐feed原分配；原单位及当前前后量永久保留。")
public class MesProductionMaterialRestoreEntity extends BaseEntity<MesProductionMaterialRestoreEntity> {
    /**
     * 所属永久恢复主账ID。
     */
    private Long reversalId;
    /**
     * 原实际耗用主账ID。
     */
    private Long operationId;
    /**
     * 精确原投料行，不猜同码FIFO或换投料。
     */
    private Long feedId;
    /**
     * 原实际投料物料编码。
     */
    private String matCode;
    /**
     * 原实际投料单位，不按当前MDM换算。
     */
    private String matUnit;
    /**
     * 原投料总量，用于必要写与精度回读。
     */
    private BigDecimal feedNum;
    /**
     * 该feed全部原allocation的实际量之和。
     */
    private BigDecimal restoredNum;
    /**
     * 恢复时当前已用量，不是历史正向before。
     */
    private BigDecimal usedBeforeNum;
    /**
     * 当前已用量减本次原分配，保留后来合法耗用。
     */
    private BigDecimal usedAfterNum;
    /**
     * 按文本ID稳定排序的完整原allocation ID JSON数组。
     */
    private String allocationIds;
    /**
     * 实际工厂归属，客户端期望值不作为执行上下文。
     */
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
