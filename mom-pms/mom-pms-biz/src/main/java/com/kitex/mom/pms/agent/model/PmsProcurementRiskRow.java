package com.kitex.mom.pms.agent.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * PMS 采购风险聚合查询的数据库投影。
 */
public final class PmsProcurementRiskRow {

    private PmsProcurementRiskRow() {
    }

    @Data
    public static class Summary {
        private Long activeOrderCount;
        private Long openLineCount;
        private Long pendingApprovalOrderCount;
        private Long overdueCommitmentLineCount;
        private Long dueSoonLineCount;
        private Long uncommittedGapLineCount;
        private Long internalDueDatePassedLineCount;
        private Long commitmentExcessLineCount;
        private Long missingUnitLineCount;
    }

    @Data
    public static class RiskLine {
        private Long orderId;
        private Long orderDetailId;
        /** 订单行关联的采购计划号，供采购员区分同物料的采购需求。 */
        private String purchasePlanNo;
        private String orderNo;
        private String orderStatus;
        private String supplierCode;
        private String supplierName;
        private String materialCode;
        private String materialName;
        private String materialUnit;
        private BigDecimal orderNum;
        private BigDecimal arrivalNum;
        private BigDecimal cancelledNum;
        private BigDecimal outstandingNum;
        private BigDecimal committedRemainingNum;
        private BigDecimal overdueCommitmentNum;
        private BigDecimal dueSoonCommitmentNum;
        private BigDecimal uncommittedGapNum;
        private LocalDate internalDeliveryDate;
        private LocalDateTime nextCommittedDeliveryTime;
        private String riskCodes;
    }
}
