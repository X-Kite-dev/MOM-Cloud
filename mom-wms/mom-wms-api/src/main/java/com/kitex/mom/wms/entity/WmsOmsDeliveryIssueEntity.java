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
 * OMS来源WMS发货计划创建/同步的不可变命令回执。
 */
@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("wms_oms_delivery_issue")
public class WmsOmsDeliveryIssueEntity extends BaseEntity<WmsOmsDeliveryIssueEntity> {
    private String requestId;
    private String requestFingerprint;
    private String action;
    private String beforeSnapshot;
    private String desiredSnapshot;
    private String afterSnapshot;
    private Long planId;
    private String orderCode;
    private Long orderDetailId;
    private String planCode;
    private String status;
    private LocalDateTime completedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
