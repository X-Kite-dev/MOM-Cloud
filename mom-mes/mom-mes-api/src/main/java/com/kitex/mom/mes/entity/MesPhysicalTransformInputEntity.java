package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/** 正式转换的逐库存实际扣减行。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_physical_transform_input")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "实物转换投入扣减行")
public class MesPhysicalTransformInputEntity extends BaseEntity<MesPhysicalTransformInputEntity> {
    private String factoryCode;
    private Long executionId;
    private Long stockId;
    private Long sourceReportId;
    private String materialCode;
    private String unit;
    private BigDecimal quantity;
    private BigDecimal beforeQuantity;
    private BigDecimal afterQuantity;
    private Long outFlowId;
    private String sourceFingerprint;
}
