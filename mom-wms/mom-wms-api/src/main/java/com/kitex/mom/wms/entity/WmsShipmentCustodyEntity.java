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
 * 一张销售出库单对应一条物流保管链，状态不可跳跃。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_shipment_custody")
@EqualsAndHashCode(callSuper = true)
public class WmsShipmentCustodyEntity extends BaseEntity<WmsShipmentCustodyEntity> {
    private String custodyNo;
    private Long outBillId;
    private String outBillNo;
    private Long deliveryId;
    private String deliveryCode;
    private String state;
    private String sourceFingerprint;
    private String carrierCode;
    private String carrierName;
    private String waybillNo;
    private String vehiclePlate;
    private String driverName;
    private String driverPhone;
    private String sealNo;
    private LocalDateTime estimatedArrivalTime;
    private String handoverBy;
    private String handoverByName;
    private LocalDateTime handoverTime;
    private String handoverEvidence;
    private String gateBy;
    private String gateByName;
    private String gatePassNo;
    private LocalDateTime departedTime;
    private String departureEvidence;
    private String recipientName;
    private String recipientContact;
    private LocalDateTime receiptTime;
    private String receiptEvidence;
    private String cancelReason;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
