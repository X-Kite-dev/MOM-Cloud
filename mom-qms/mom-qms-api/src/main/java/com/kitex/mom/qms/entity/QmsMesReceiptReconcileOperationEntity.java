package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("qms_mes_receipt_reconcile_operation")
public class QmsMesReceiptReconcileOperationEntity extends BaseEntity<QmsMesReceiptReconcileOperationEntity> {
    private String factoryCode;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long reconcileId;
    private Long revision;
    private String requestId;
    private String fingerprint;
    private String operationType;
    private String beforeStatus;
    private String afterStatus;
    private String evidence;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorId;
    private String operatorCode;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }
}
