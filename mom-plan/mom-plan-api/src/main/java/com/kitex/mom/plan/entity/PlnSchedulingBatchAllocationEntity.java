package com.kitex.mom.plan.entity;

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
@TableName("pln_scheduling_batch_allocation")
@EqualsAndHashCode(callSuper = true)
public class PlnSchedulingBatchAllocationEntity extends BaseEntity<PlnSchedulingBatchAllocationEntity> {
    private Long taskId;
    private Long batchId;
    private String batchNo;
    private Integer allocationSeq;
    private Long issueId;
    private String issueNo;
    private BigDecimal issueQuantitySnapshot;
    private BigDecimal allocatedQuantity;
    private String productCode;
    private String demandWarehouseCode;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
