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
 * WMS采购退供完成事件永久收件账。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("pms_supplier_return_inbox")
@EqualsAndHashCode(callSuper = true)
public class PmsSupplierReturnInboxEntity extends BaseEntity<PmsSupplierReturnInboxEntity> {
    private String eventId;
    private Long returnId;
    private Long wmsNoticeId;
    private Long wmsOutBillId;
    private String fingerprint;
    private String payload;
    private String status;
    private LocalDateTime receivedTime;
    private LocalDateTime processedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
