package com.kitex.mom.plan.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * MPS候选的生产批量整形与RCCP可解释负荷平衡建议。
 *
 * <p>建议只改变候选时间桶，不直接发布计划。计划员应用建议后，仍须经过MPS提交、
 * 异岗复核、证据重算和正式发布。</p>
 */
public class PlnMpsCapacityBalanceDTO {

    @Data
    @Accessors(chain = true)
    public static class Result {
        /**
         * PASS、PROPOSED、UNRESOLVED或UNVERIFIED。
         */
        private String status;
        private String mode;
        private BigDecimal maxLoadRate;
        /**
         * 本次采用的正式生产批量策略目录指纹。
         */
        private String lotPolicyEvidenceFingerprint;
        /**
         * 尚未配置正式生产批量策略、继续沿用精确需求量的产品。
         */
        private List<String> productsWithoutLotPolicy;
        private PlnMpsCapacityDTO.Preview original;
        private List<String> messages;
        private List<Proposal> proposals;
    }

    @Data
    @Accessors(chain = true)
    public static class Proposal {
        /**
         * LOT_SIZING_ONLY、MIN_CHANGE_FORWARD、DELIVERY_FIRST或QUALIFIED_RESOURCE_BALANCE。
         */
        private String strategy;
        private String strategyName;
        private Boolean feasible;
        private Integer changedBucketCount;
        /**
         * 相对默认/原选产线发生显式资源调整的建议桶数。
         */
        private Integer resourceChangedBucketCount;
        /**
         * 建议方案中按产线产品族顺序估算的换型次数。
         */
        private Integer changeoverCount;
        /**
         * 建议方案的产线级换型占用分钟。
         */
        private BigDecimal changeoverMinutes;
        private Integer splitBucketCount;
        private BigDecimal pulledInQuantity;
        private BigDecimal delayedQuantity;
        private BigDecimal unresolvedQuantity;
        private Integer maxProjectedLateDays;
        private String explanation;
        private String evidenceFingerprint;
        private List<String> messages;
        private List<Line> lines;
        private PlnMpsCapacityDTO.Preview preview;
    }

    @Data
    @Accessors(chain = true)
    public static class Line {
        private Long demandId;
        private String demandNo;
        private Integer sourceBucketNo;
        private Integer bucketNo;
        private String productCode;
        private BigDecimal planQuantity;
        private LocalDate sourceDueDate;
        private LocalDate originalDate;
        private LocalDate plannedDate;
        private Integer mrpPriority;
        /**
         * KEEP、PULL_IN或DELAY。
         */
        private String action;
        private String resourceCode;
        private Long lotPolicyId;
        private String lotPolicyNo;
        private Integer lotPolicyVersion;
        private BigDecimal minimumBatchQuantity;
        private BigDecimal economicBatchQuantity;
        private BigDecimal maximumBatchQuantity;
        private BigDecimal batchMultiple;
        private String reason;
    }
}
