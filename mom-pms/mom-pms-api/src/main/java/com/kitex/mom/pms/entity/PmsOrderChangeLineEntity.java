package com.kitex.mom.pms.entity;

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

/**
 * 订单变更申请冻结的逐行数量及供应证据。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pms_order_change_line")
@EqualsAndHashCode(callSuper = true)
public class PmsOrderChangeLineEntity extends BaseEntity<PmsOrderChangeLineEntity> {
    private Long changeRequestId;
    private Long orderDetailId;
    private String pmsPlanNo;
    private String planDemandNo;
    private String materialCode;
    private String materialName;
    private String materialUnit;
    private LocalDate deliveryDate;
    private LocalDate targetDeliveryDate;
    private BigDecimal orderQuantitySnapshot;
    private BigDecimal targetOrderQuantity;
    private BigDecimal quantityDelta;
    private BigDecimal arrivalQuantitySnapshot;
    private BigDecimal cancelledQuantity;
    private BigDecimal originalPrice;
    private BigDecimal targetPrice;
    private Long originalPriceId;
    private Long targetPriceId;
    private Long originalAuthorizationId;
    private Long targetAuthorizationId;
    private Boolean supplyAffected;
    private String changeSummary;
    private Integer activeCommitmentCount;
    private String activeCommitmentIds;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
