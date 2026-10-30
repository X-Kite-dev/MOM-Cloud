package com.kitex.mom.oms.entity;

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
import java.time.LocalDateTime;

/**
 * OMS异岗变更实际写入PLAN需求的逐交付行不可变回执。
 */
@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("oms_order_plan_revision")
public class OmsOrderPlanRevisionEntity extends BaseEntity<OmsOrderPlanRevisionEntity> {
    private Long versionId;
    private Long orderDetailId;
    private Long demandId;
    private String orderCode;
    private String demandNo;
    /**
     * DRAFT_UPDATE、MRP_DRAFT_RECALCULATE或MRP_SUPPLY_CASCADE_RECALCULATE。
     */
    private String applyMode;
    private String beforeState;
    private BigDecimal beforeQuantity;
    private LocalDate beforeDate;
    private BigDecimal afterQuantity;
    private LocalDate afterDate;
    private String beforeFingerprint;
    private String afterFingerprint;
    private LocalDateTime appliedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
