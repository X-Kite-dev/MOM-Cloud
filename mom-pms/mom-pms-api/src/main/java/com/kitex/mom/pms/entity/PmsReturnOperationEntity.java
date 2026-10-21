package com.kitex.mom.pms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 采购退供不可变操作履历。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("pms_return_operation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "采购退供不可变操作履历")
public class PmsReturnOperationEntity extends BaseEntity<PmsReturnOperationEntity> {
    private Long returnId;
    private String requestId;
    private String operationType;
    private String beforeStatus;
    private String afterStatus;
    private String operatorCode;
    private String operatorName;
    private LocalDateTime operationTime;
    private String detail;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
