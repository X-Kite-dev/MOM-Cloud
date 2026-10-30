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

@Data
@Accessors(chain = true)
@TenantTable
@TableName("oms_shipment_correction_event")
@EqualsAndHashCode(callSuper = true)
public class OmsShipmentCorrectionEventEntity extends BaseEntity<OmsShipmentCorrectionEventEntity> {
    private String requestId;
    private String requestFingerprint;
    private Long correctionId;
    private String correctionNo;
    private Long wmsOutBillId;
    private String wmsOutBillNo;
    private Long wmsPlanId;
    private String wmsPlanCode;
    private String orderCode;
    private Long orderDetailId;
    private String productCode;
    private BigDecimal correctedNum;
    private LocalDateTime correctedTime;
    private BigDecimal omsDeliveredNum;
    private String status;
    private LocalDateTime completedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
