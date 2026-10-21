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
 * WMS原子撤回回执：与计划逻辑删除同事务提交，历史不可覆写。
 */
@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("wms_oms_delivery_withdrawal")
public class WmsOmsDeliveryWithdrawalEntity extends BaseEntity<WmsOmsDeliveryWithdrawalEntity> {
    private String requestId;
    private String requestFingerprint;
    private Long planId;
    private String orderCode;
    private Long orderDetailId;
    private String planCode;
    private String beforeSnapshot;
    private String reason;
    private String status;
    private LocalDateTime completedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
