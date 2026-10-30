package com.kitex.mom.plan.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 补产进度的薄投影，避免向页面暴露整份生产计划。
 */
public final class PlanQualitySupplementProgressDTO {
    private PlanQualitySupplementProgressDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class RootPlan {
        private Long id;
        private String issueNo;
        private Integer bucketNo;
        private LocalDate demandDate;
        private BigDecimal quantity;
        private BigDecimal completedQuantity;
        private String status;
        private Boolean locked;
    }
}
