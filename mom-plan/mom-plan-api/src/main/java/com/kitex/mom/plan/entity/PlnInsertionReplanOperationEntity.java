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

/**
 * 紧急插单评估、发布和回退的不可变操作履历。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_insertion_replan_operation")
@EqualsAndHashCode(callSuper = true)
public class PlnInsertionReplanOperationEntity extends BaseEntity<PlnInsertionReplanOperationEntity> {
    private Long caseId;
    private String requestId;
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
