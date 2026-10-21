package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * 原位置至全部真实库存的当量边；不是转换扣减或产出分配。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TenantTable
@TableName("mes_physical_position_allocation")
@Schema(description = "原位置至全部真实库存的当量边；不是转换扣减或产出分配")
public class MesPhysicalPositionAllocationEntity extends BaseEntity<MesPhysicalPositionAllocationEntity> {
    /**
     * 与原采集主账一致的工厂。
     */
    private String factoryCode;
    /**
     * 原位置观察主账ID。
     */
    private Long surveyId;
    /**
     * 只能引用原STOCKED位置，不由载具成员顺序推定。
     */
    private String positionCode;
    /**
     * 只能引用原精确来源全集中的真实库存。
     */
    private Long stockId;
    /**
     * 该位来自该库存的正量SN当量，逐位合计1、逐stock完整守恒。
     */
    private BigDecimal equivalentQuantity;
}
