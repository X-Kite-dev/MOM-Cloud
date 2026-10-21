package com.kitex.mom.oms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 每个活动OMS交付行一条自动扫描状态；成功证据仍以风险操作账为准。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("oms_fulfillment_risk_scan_state")
@EqualsAndHashCode(callSuper = true)
public class OmsFulfillmentRiskScanStateEntity extends BaseEntity<OmsFulfillmentRiskScanStateEntity> {
    private Long orderDetailId;
    private String orderCode;
    private String productCode;
    private String status;
    private Integer revision;
    private Integer successCount;
    private Integer failureCount;
    private LocalDateTime lastScanTime;
    private LocalDateTime lastSuccessTime;
    private LocalDateTime nextScanTime;
    private String lastRequestId;
    private Integer lastRiskCount;
    private String lastError;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
