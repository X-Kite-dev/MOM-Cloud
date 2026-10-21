package com.kitex.mom.pms.entity;

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
 * 采购订单变更提交、复核、失效及执行的不可变操作账。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pms_order_change_operation")
@EqualsAndHashCode(callSuper = true)
public class PmsOrderChangeOperationEntity extends BaseEntity<PmsOrderChangeOperationEntity> {
    private Long changeRequestId;
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
