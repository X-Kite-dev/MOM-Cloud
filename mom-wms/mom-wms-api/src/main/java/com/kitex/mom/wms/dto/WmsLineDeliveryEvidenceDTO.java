package com.kitex.mom.wms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * MES签收前实时读取的WMS线边配送证据。
 */
@Data
@Accessors(chain = true)
public class WmsLineDeliveryEvidenceDTO {
    private Long taskId;
    private String taskNo;
    private String state;
    private Long outBillId;
    private Long outBillDetailId;
    private String factoryCode;
    private String matCode;
    private BigDecimal plannedNum;
    private BigDecimal deliveredNum;
    private BigDecimal recoveredNum;
    private BigDecimal receivableNum;
    private String sourceFingerprint;
}
