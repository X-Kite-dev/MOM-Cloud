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
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * OMS交付行履约承诺的不可变历史快照。
 *
 * <p>快照只在PLAN、MES、PMS、WMS和质量可交事实均可解释时形成，既保存摘要指标，
 * 也冻结当时完整跨域生命周期证据，避免事后用当前数据倒推过去承诺。</p>
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("oms_fulfillment_snapshot")
@EqualsAndHashCode(callSuper = true)
public class OmsFulfillmentSnapshotEntity extends BaseEntity<OmsFulfillmentSnapshotEntity> {
    private Long orderDetailId;
    private String orderCode;
    private String productCode;
    private Integer snapshotVersion;
    private Long previousSnapshotId;
    private String captureType;
    private String captureReason;
    private String requestId;
    private String requestFingerprint;
    private String evidenceFingerprint;
    private String overallStatus;
    private String riskLevel;
    private LocalDate promisedDate;
    private BigDecimal orderedQuantity;
    private BigDecimal producedQuantity;
    private BigDecimal stockedQuantity;
    private BigDecimal deliveredQuantity;
    private BigDecimal remainingQuantity;
    private BigDecimal currentOrderReservedQuantity;
    private BigDecimal netDeliverableQuantity;
    private BigDecimal deliverableShortageQuantity;
    private Integer promisedDateShiftDays;
    private BigDecimal orderedQuantityDelta;
    private BigDecimal producedQuantityDelta;
    private BigDecimal stockedQuantityDelta;
    private BigDecimal deliveredQuantityDelta;
    private BigDecimal remainingQuantityDelta;
    private BigDecimal currentOrderReservedQuantityDelta;
    private BigDecimal netDeliverableQuantityDelta;
    private BigDecimal deliverableShortageQuantityDelta;
    private String summarySnapshot;
    private String milestoneSnapshot;
    private String lifecycleSnapshot;
    private String operatorCode;
    private String operatorName;
    private LocalDateTime capturedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
