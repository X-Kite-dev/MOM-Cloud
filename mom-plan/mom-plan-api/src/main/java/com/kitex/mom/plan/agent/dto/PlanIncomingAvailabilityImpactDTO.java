package com.kitex.mom.plan.agent.dto;

import com.kitex.mom.plan.dto.PlanMrpShortageLineDTO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 采购来料完成后对PLAN产品需求齐套状态的只读影响证据。
 */
public final class PlanIncomingAvailabilityImpactDTO {

    private PlanIncomingAvailabilityImpactDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        @NotBlank
        private String factoryCode;
        @NotEmpty
        private List<SourceReference> sources;
    }

    /**
     * PMS已核验的采购订单行与采购计划来源引用；PLAN仍须使用本地台账反向验证。
     */
    @Data
    @Accessors(chain = true)
    public static class SourceReference {
        private String purchaseOrderDetailId;
        private String purchasePlanNo;
        private String sourceIssueMonthId;
        private String materialCode;
    }

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String factoryCode;
        private LocalDateTime observedAt;
        private Integer sourceCount;
        private Integer resolvedSourceCount;
        private List<DemandImpact> demands;
        private List<String> dataGaps;
        private List<String> limitations;
        private List<String> evidenceRefs;
        private String factFingerprint;
    }

    @Data
    @Accessors(chain = true)
    public static class DemandImpact {
        private String planDemandId;
        private String planDemandNo;
        private List<String> purchaseOrderDetailIds;
        private List<String> purchasePlanNos;
        private String saleOrderCode;
        private String materialAvailabilityStatus;
        private LocalDateTime materialAvailableTime;
        private Integer materialCount;
        private Integer shortageCount;
        private Integer reservationRiskCount;
        private Integer actionRequiredCount;
        private Integer supplyEvidenceGapCount;
        private String shortageFactFingerprint;
        private List<PlanMrpShortageLineDTO> shortageLines;
    }
}
