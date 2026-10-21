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

/** 资源异常局部重排的不可变操作履历。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_resource_replan_operation")
@EqualsAndHashCode(callSuper = true)
public class PlnResourceReplanOperationEntity extends BaseEntity<PlnResourceReplanOperationEntity> {
    private Long caseId;
    private String requestId;
    private String operationType;
    private String beforeStatus;
    private String afterStatus;
    private String content;
    /** 人工恢复复核命令的冻结指纹，供永久请求号重放核对。 */
    private String commandFingerprint;
    private String operatorCode;
    private String operatorName;
    private LocalDateTime operationTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
