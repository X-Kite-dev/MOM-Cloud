package com.kitex.mom.oms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * WMS发运事实OMS收件箱；交付行加量与永久回执同一OMS事务。
 */
@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("oms_delivery_event")
public class OmsDeliveryEventEntity extends BaseEntity<OmsDeliveryEventEntity> {
    private String requestId;
    private String requestFingerprint;
    private Long wmsBillId;
    private String wmsBillNo;
    private Long wmsPlanId;
    private String wmsPlanCode;
    private String orderCode;
    private Long orderDetailId;
    private String productCode;
    private BigDecimal shippedNum;
    private LocalDateTime shippedTime;
    private String shipmentType;
    private Long resolutionCaseId;
    private BigDecimal omsDeliveredNum;
    private BigDecimal legacyBaselineNum;
    private String status;
    private LocalDateTime completedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
