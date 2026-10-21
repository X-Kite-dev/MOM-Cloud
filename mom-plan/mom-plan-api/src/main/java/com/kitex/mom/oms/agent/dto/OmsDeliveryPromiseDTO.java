package com.kitex.mom.oms.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 销售订单交期答复的受控事实、情景和缺口。 */
public final class OmsDeliveryPromiseDTO {
    private OmsDeliveryPromiseDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        @NotBlank private String factoryCode;
        @NotBlank @Size(max = 200) private String orderCode;
        @Size(max = 200) private String productCode;
        /** 明确提供时才从内部就绪日期推导客户候选日期；不传则不猜物流时长。 */
        @Min(0) @Max(30) private Integer dispatchLeadDays;
        @Min(1) @Max(50) private Integer lineLimit = 20;
    }

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String factoryCode;
        private String orderCode;
        private String productCode;
        private String customerCode;
        private String customerName;
        private LocalDateTime observedAt;
        private Long matchedLineCount;
        private Integer returnedLineCount;
        private String overallPromiseStatus;
        private LocalDate latestCurrentPromisedDate;
        private LocalDate latestCandidatePromiseDate;
        private Boolean candidateComplete;
        private Integer dispatchLeadDays;
        private List<LineAssessment> lines = new ArrayList<>();
        private List<String> recommendedActions = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        private Boolean truncated;
    }

    @Data
    @Accessors(chain = true)
    public static class LineAssessment {
        private Long orderDetailId;
        private String productCode;
        private String productName;
        private String productUnit;
        private LocalDate currentPromisedDate;
        private BigDecimal orderedQuantity;
        private BigDecimal deliveredQuantity;
        private BigDecimal remainingQuantity;
        private BigDecimal netDeliverableQuantity;
        private LocalDate projectedInternalReadyDate;
        private String internalReadyBasis;
        private LocalDate currentDispatchPlanDate;
        private LocalDate candidatePromiseDate;
        /** COMPLETED、ON_TIME_SUPPORTED、LATE_RISK、INTERNAL_DATE_ONLY、BLOCKED、INSUFFICIENT_EVIDENCE。 */
        private String promiseStatus;
        /** HIGH、MEDIUM、LOW。 */
        private String confidence;
        private String overallFulfillmentStatus;
        private String riskLevel;
        private List<Scenario> scenarios = new ArrayList<>();
        private List<String> blockers = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class Scenario {
        private String scenarioCode;
        private String scenarioName;
        private LocalDate internalReadyDate;
        private LocalDate customerPromiseDate;
        private BigDecimal supportQuantity;
        private String status;
        private List<String> assumptions = new ArrayList<>();
    }
}
