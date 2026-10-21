package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_aps_batch_allocation")
@EqualsAndHashCode(callSuper = true)
public class MesApsBatchAllocationEntity extends BaseEntity<MesApsBatchAllocationEntity> {
    private Long apsTaskId;
    private Long apsBatchId;
    private String apsBatchNo;
    private String apsBatchFingerprint;
    private Long mesTaskId;
    private String mesTaskCode;
    private Integer allocationSeq;
    private Long issueId;
    private String issueNo;
    private BigDecimal issueQuantitySnapshot;
    private BigDecimal allocatedQuantity;
    private BigDecimal completedQuantity;
    private BigDecimal inStockQuantity;
    private BigDecimal unqualifiedQuantity;
    private BigDecimal qualityIsolatedQuantity;
    private BigDecimal qualityReleasedQuantity;
    private BigDecimal qualityRejectedQuantity;
    private BigDecimal scrapQuantity;
    private BigDecimal reworkQuantity;
    private BigDecimal reworkCompletedQuantity;
    private BigDecimal reworkQualifiedQuantity;
    private BigDecimal reworkRejectedQuantity;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
