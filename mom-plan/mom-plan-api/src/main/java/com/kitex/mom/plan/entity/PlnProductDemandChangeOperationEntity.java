package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 需求变更不可变操作履历，兼作复核和取消命令的永久幂等账。 */
@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("pln_product_demand_change_operation")
public class PlnProductDemandChangeOperationEntity extends BaseEntity<PlnProductDemandChangeOperationEntity> {
    private Long changeId;
    private String requestId;
    private String requestFingerprint;
    private String operationType;
    private String beforeStatus;
    private String afterStatus;
    private String content;
    private String operatorCode;
    private String operatorName;
    private LocalDateTime operationTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
