package com.kitex.mom.oms.agent.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 销售订单交期答复的受控事实、情景和缺口。
 */
public final class OmsDeliveryPromiseDTO {
    private OmsDeliveryPromiseDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        @NotBlank
        private String factoryCode;
        /**
         * 精确订单入口使用订单跟踪主键文本，避免浏览器长整数精度损失。
         */
        @Pattern(regexp = "[1-9][0-9]{0,18}")
        private String orderTrackId;
        @NotBlank
        @Size(max = 200)
        private String orderCode;
        @Size(max = 200)
        private String productCode;
        /**
         * 明确提供时才从内部就绪日期推导客户候选日期；不传则不猜物流时长。
         */
        @Min(0)
        @Max(30)
        private Integer dispatchLeadDays;
        @Min(1)
        @Max(50)
        private Integer lineLimit = 20;
    }

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String factoryCode;
        private Boolean orderScoped;
        private String orderTrackId;
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
        /**
         * 固定计算边界，不作为业务缺数。
         */
        private List<String> limitations = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        /**
         * 排除观察时刻后的权威事实指纹。
         */
        private String factFingerprint;
        private FormalReply latestFormalReply;
        private Boolean truncated;
    }

    /**
     * OMS原交期答复状态机的最近正式记录；Agent不复制或修改该状态。
     */
    @Data
    @Accessors(chain = true)
    public static class FormalReply {
        private String promiseId;
        private String promiseNo;
        private String status;
        private String orderChangeStatus;
        private LocalDateTime readyTime;
        private String replyChannel;
        private String replyReference;
        private LocalDateTime repliedTime;
        /**
         * 已答复候选是否仍与当前订单交付行一致。
         */
        private Boolean currentScheduleMatched;
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
        /**
         * COMPLETED、ON_TIME_SUPPORTED、LATE_RISK、INTERNAL_DATE_ONLY、BLOCKED、INSUFFICIENT_EVIDENCE。
         */
        private String promiseStatus;
        /**
         * HIGH、MEDIUM、LOW。
         */
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
