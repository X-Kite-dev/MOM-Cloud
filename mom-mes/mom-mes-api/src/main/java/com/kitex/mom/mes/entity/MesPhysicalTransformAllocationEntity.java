package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/** 正式转换的逐来源至逐输出当量分配，不以单一sourceReportId代替多来源。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_physical_transform_allocation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "实物转换来源分配边")
public class MesPhysicalTransformAllocationEntity extends BaseEntity<MesPhysicalTransformAllocationEntity> {
    private String factoryCode;
    private Long executionId;
    private Long inputId;
    private Long outputId;
    private Long stockId;
    private String outputKey;
    private BigDecimal equivalentQuantity;
}
