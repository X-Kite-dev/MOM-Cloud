package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.List;

public final class WmsTransferReceiptDTO {
    private WmsTransferReceiptDTO() {
    }

    @Data
    public static class Command {
        private Long allocationBillId;
        private String requestId;
        private Boolean finalReceipt;
        private String reasonCode;
        private String reasonDescription;
        private String responsibleType;
        private String evidence;
        private List<Line> lines;
    }

    @Data
    public static class Line {
        private Long sourceFlowId;
        @Schema(description = "本批实收物理数量；最终接收允许为0以申报整行短收")
        private BigDecimal actualReceivedQuantity;
        private String targetSiteCode;
        private String targetContainerCode;
    }

    @Data
    @Accessors(chain = true)
    public static class Result {
        private Long receiptId;
        private String receiptNo;
        private Long allocationBillId;
        private String allocationState;
        private Long varianceCaseId;
        private String varianceCaseNo;
        private String varianceStatus;
    }
}
