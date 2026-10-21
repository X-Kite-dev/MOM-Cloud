package com.kitex.mom.plan.entity;

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

/** ERP 投递状态变化及人工补偿履历。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_erp_integration_operation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "ERP投递操作履历")
public class PlnErpIntegrationOperationEntity extends BaseEntity<PlnErpIntegrationOperationEntity> {
    private Long outboxId;
    private String operationType;
    private String beforeStatus;
    private String afterStatus;
    private String requestFingerprint;
    private String reason;
    private String operatorCode;
    private String operatorName;
    private LocalDateTime operationTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
