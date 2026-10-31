package com.kitex.mom.qms.dto;

import lombok.Data;

import java.math.BigDecimal;

/** QMS internal read projections; no persistence or public event contract. */
public final class QmsInspectionTraceRows {
    private QmsInspectionTraceRows() {}

    @Data
    public static class Task {
        private Long id;
        private Long tenantId;
        private String factoryCode;
        private String sourceTaskCode;
        private String sourceRequestId;
        private String orderNo;
        private String matCode;
        private String matName;
        private String matSpec;
        private String processCode;
        private String processName;
        private String batchNo;
        private Long standardId;
        private String standardName;
        private String deptCode;
        private Integer num;
        private Integer inspectionNum;
        private String status;
        private String result;
    }

    @Data
    public static class Handle {
        private Long id;
        private Long tenantId;
        private String factoryCode;
        private Long taskId;
        private Integer handleNum;
        private String handleResult;
        private String handleRemark;
    }

    @Data
    public static class Order {
        private Long id;
        private String delFlag;
        private Long tenantId;
        private String factoryCode;
        private String inspectionType;
        private String requestId;
        private Long inspectionTaskId;
        private Long handleRecordId;
        private String originalMesTaskCode;
        private String originalMesProduceTaskCode;
        private String reworkProcessCode;
        private String matCode;
        private String matName;
        private String matSpec;
        private String batchNo;
        private BigDecimal quantity;
        private String reason;
        private String status;
        private String mesTaskCode;
        private BigDecimal mesCompletedQuantity;
        private Long reinspectionTaskId;
        private String reinspectionStatus;
    }
}
