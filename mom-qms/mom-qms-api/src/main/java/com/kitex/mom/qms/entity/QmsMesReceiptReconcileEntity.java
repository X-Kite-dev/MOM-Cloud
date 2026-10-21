package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("qms_mes_receipt_reconcile")
public class QmsMesReceiptReconcileEntity extends BaseEntity<QmsMesReceiptReconcileEntity> {
    private String factoryCode;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long badId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long detailId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long caseId;
    private String ncrNo;
    private String decisionKey;
    private String decisionSnapshot;
    private String status;
    private Long revision;
    private Long checkCount;
    private Integer failureCount;
    private LocalDateTime lastCheckTime;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime nextCheckTime;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long lastReceiptId;
    private String lastResult;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }
}
