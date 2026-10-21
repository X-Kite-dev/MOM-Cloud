package com.kitex.mom.wms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.List;

/**
 * 客户交付异常协调执行命令。
 */
public final class WmsShipmentExceptionResolutionCommand {
    private WmsShipmentExceptionResolutionCommand() {
    }

    @Data
    @Accessors(chain = true)
    public static class Apply {
        private String requestId;
        private Long custodyId;
        private String returnWarehouse;
        private String returnWarehouseName;
        private String reason;
        private String evidence;
        private List<Line> lines;
    }

    @Data
    @Accessors(chain = true)
    public static class Line {
        private Long receiptDetailId;
        private BigDecimal returnNum;
        private BigDecimal reissueNum;
        private BigDecimal refundNum;
        private BigDecimal concessionNum;
        private BigDecimal claimNum;
        private BigDecimal refundAmount;
        private BigDecimal concessionAmount;
        private BigDecimal claimAmount;
        private String remark;
    }

    @Data
    @Accessors(chain = true)
    public static class Decision {
        private String requestId;
        private String remark;
        private String evidence;
    }

    @Data
    @Accessors(chain = true)
    public static class Settlement {
        private String requestId;
        private String refundReference;
        private String concessionReference;
        private String claimReference;
        private String evidence;
    }

    @Data
    @Accessors(chain = true)
    public static class Reconcile {
        private String requestId;
        private String summary;
        private String evidence;
    }
}
