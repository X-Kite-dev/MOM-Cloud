package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_transfer_variance_policy")
@EqualsAndHashCode(callSuper = true)
public class WmsTransferVariancePolicyEntity extends BaseEntity<WmsTransferVariancePolicyEntity> {
    private String policyCode;
    private String policyName;
    private String sourceWarehouseCode;
    private String targetWarehouseCode;
    private String materialCode;
    private BigDecimal shortTolerancePercent;
    private BigDecimal shortToleranceQuantity;
    private BigDecimal overTolerancePercent;
    private BigDecimal overToleranceQuantity;
    private Boolean planReviewOutsideTolerance;
    private Boolean qualityReviewForDamage;
    private Boolean qualityReviewForOver;
    private Integer policyPriority;
    private LocalDate effectiveStartDate;
    private LocalDate effectiveEndDate;
    private Boolean status;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
