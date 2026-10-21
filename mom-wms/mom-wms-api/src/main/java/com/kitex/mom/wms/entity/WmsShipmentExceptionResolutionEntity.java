package com.kitex.mom.wms.entity;

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
import java.util.List;

/**
 * 客户实收异常的退货、补发、结算与索赔协调执行单。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_shipment_exception_resolution")
@EqualsAndHashCode(callSuper = true)
public class WmsShipmentExceptionResolutionEntity extends BaseEntity<WmsShipmentExceptionResolutionEntity> {
    private String caseNo;
    private Long custodyId;
    private String state;
    private String returnWarehouse;
    private String returnWarehouseName;
    private String applicantCode;
    private String applicantName;
    private LocalDateTime appliedTime;
    private String approverCode;
    private String approverName;
    private LocalDateTime approvedTime;
    private String approvalRemark;
    private String reason;
    private String evidence;
    private Long returnId;
    private Long reissueDeliveryId;
    private BigDecimal refundAmount;
    private String refundReference;
    private BigDecimal concessionAmount;
    private String concessionReference;
    private BigDecimal claimAmount;
    private String claimReference;
    private String settlementEvidence;
    private LocalDateTime settlementTime;
    private String closeSummary;
    private LocalDateTime closedTime;
    @TableField(exist = false)
    private List<WmsShipmentExceptionResolutionDetailEntity> details;
    @TableField(exist = false)
    private List<WmsShipmentExceptionResolutionOperationEntity> operations;
    @TableField(exist = false)
    private Boolean returnCompleted;
    @TableField(exist = false)
    private Boolean reissueCompleted;
    @TableField(exist = false)
    private Boolean settlementCompleted;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
