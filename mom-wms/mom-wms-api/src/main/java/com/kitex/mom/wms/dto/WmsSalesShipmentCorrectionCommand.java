package com.kitex.mom.wms.dto;

import lombok.Data;

/**
 * 销售出库记账纠错命令；只适用于货物确认尚未离场。
 */
@Data
public class WmsSalesShipmentCorrectionCommand {
    private Long outBillId;
    private String reason;
    private String rejectReason;
}
