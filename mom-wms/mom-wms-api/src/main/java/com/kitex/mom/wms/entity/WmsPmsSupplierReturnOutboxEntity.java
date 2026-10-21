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

/**
 * WMS实际退供向PMS回写的可靠Outbox。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("wms_pms_supplier_return_outbox")
@EqualsAndHashCode(callSuper = true)
public class WmsPmsSupplierReturnOutboxEntity extends BaseEntity<WmsPmsSupplierReturnOutboxEntity> {
    private String eventId;
    private Long returnId;
    private Long noticeId;
    private Long outBillId;
    private String fingerprint;
    private String payload;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptTime;
    private String lastError;
    private LocalDateTime deliveredTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
