package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_transfer_variance_operation")
@EqualsAndHashCode(callSuper = true)
public class WmsTransferVarianceOperationEntity extends BaseEntity<WmsTransferVarianceOperationEntity> {
    private Long caseId;
    private String requestId;
    private String requestSnapshot;
    private String operationType;
    private String beforeStatus;
    private String afterStatus;
    private String operationContent;
    private String operatorCode;
    private String operatorName;
    private LocalDateTime operationTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
