package com.kitex.mom.wms.dto;

import lombok.Data;

/**
 * 销售预留漂移待办命令；结果未知重试必须复用原请求号与原载荷。
 */
public class WmsSalesReservationDriftDTO {

    @Data
    public static class Query {
        private String status;
        private String orderCode;
        private String productCode;
        private String caseNo;
    }

    @Data
    public static class Scan {
        private String requestId;
    }

    @Data
    public static class Submit {
        private String requestId;
        private String expectedEvidenceFingerprint;
        private String reason;
    }

    @Data
    public static class Review {
        private String requestId;
        /**
         * APPROVE、REJECT；PENDING_CLOSE 待办使用 CLOSE。
         */
        private String decision;
        private String expectedEvidenceFingerprint;
        private String comment;
    }
}
