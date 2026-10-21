package com.kitex.mom.pms.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * PMS 活动采购订单、到货和正式供应商承诺风险模型，供智能体只读问数使用。
 */
public final class PmsProcurementRiskDTO {

    private PmsProcurementRiskDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "PMS 采购履约风险受控查询")
    public static class Query {
        @NotBlank
        private String factoryCode;
        @Min(1)
        @Max(90)
        private Integer horizonDays = 7;
        @Min(1)
        @Max(50)
        private Integer limit = 20;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "PMS 采购履约风险权威证据")
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String factoryCode;
        private LocalDateTime observedAt;
        private LocalDateTime horizonEnd;
        private Summary summary;
        private List<RiskLine> riskLines = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        private Boolean truncated;
    }

    @Data
    @Accessors(chain = true)
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
    @Accessors(chain = true)
    public static class RiskLine {
        private Long orderId;
        private Long orderDetailId;
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
        private List<String> riskTypes = new ArrayList<>();
    }
}
