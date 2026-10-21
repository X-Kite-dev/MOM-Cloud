package com.kitex.mom.wms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 销售发运承运交接、离场和客户实收命令。
 */
public final class WmsShipmentCustodyCommand {
    private WmsShipmentCustodyCommand() {
    }

    @Data
    @Accessors(chain = true)
    public static class Handover {
        private String requestId;
        private Long outBillId;
        private String carrierCode;
        private String carrierName;
        private String waybillNo;
        private String vehiclePlate;
        private String driverName;
        private String driverPhone;
        private String sealNo;
        private LocalDateTime estimatedArrivalTime;
        private String evidence;
    }

    @Data
    @Accessors(chain = true)
    public static class Cancel {
        private String requestId;
        private String reason;
        private String evidence;
    }

    @Data
    @Accessors(chain = true)
    public static class Depart {
        private String requestId;
        private String gatePassNo;
        private String evidence;
    }

    @Data
    @Accessors(chain = true)
    public static class Receipt {
        private String requestId;
        private String recipientName;
        private String recipientContact;
        private LocalDateTime receiptTime;
        private String evidence;
        private List<ReceiptLine> lines;
    }

    @Data
    @Accessors(chain = true)
    public static class ReceiptLine {
        private Long outBillDetailId;
        private BigDecimal signedNum;
        private BigDecimal rejectedNum;
        private BigDecimal damagedNum;
        private BigDecimal lostNum;
        private String exceptionReason;
        private String responsibility;
    }
}
