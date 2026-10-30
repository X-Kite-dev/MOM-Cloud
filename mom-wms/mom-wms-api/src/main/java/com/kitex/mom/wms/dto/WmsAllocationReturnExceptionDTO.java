package com.kitex.mom.wms.dto;

import lombok.Data;

public final class WmsAllocationReturnExceptionDTO {
    private WmsAllocationReturnExceptionDTO() {
    }

    @Data
    public static class CaseQuery {
        private String caseNo;
        private String status;
        private Long allocationBillId;
    }

    @Data
    public static class Report {
        private Long allocationBillId;
        private String requestId;
        private String reasonCode;
        private String reasonDescription;
        private String evidence;
    }

    @Data
    public static class Review {
        private Long caseId;
        private String requestId;
        private String decision;
        private String comment;
        private String dispositionCode;
        private String referenceNo;
    }
}
