package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.List;

@Data
@Accessors(chain = true)
@Schema(description = "MES工单/工序任务到PLAN来源计划的权威分配映射")
public class MesPlanSourceMappingDTO {
    private String requestedTaskCode;
    private String requestedProduceTaskCode;
    private Long mesTaskId;
    private String mesTaskCode;
    private Long produceTaskId;
    private String produceTaskCode;
    private String processCode;
    private String processName;
    private String factoryCode;
    /**
     * APS_ALLOCATION、REWORK_SOURCE、LEGACY_PLAN_NO或NO_PLAN_SOURCE。
     */
    private String mappingStatus;
    private Boolean mappingComplete;
    private String mappingMessage;
    private String mappingFingerprint;
    private List<Allocation> allocations;

    @Data
    @Accessors(chain = true)
    public static class Allocation {
        private Long apsTaskId;
        private Long apsBatchId;
        private String apsBatchNo;
        private Integer allocationSeq;
        private Long issueId;
        private String issueNo;
        private BigDecimal issueQuantitySnapshot;
        private BigDecimal allocatedQuantity;
        private BigDecimal completedQuantity;
        private BigDecimal unqualifiedQuantity;
        private BigDecimal qualityIsolatedQuantity;
        private BigDecimal qualityReleasedQuantity;
        private BigDecimal qualityRejectedQuantity;
        private BigDecimal scrapQuantity;
        private BigDecimal reworkQuantity;
        private BigDecimal reworkCompletedQuantity;
        private BigDecimal reworkQualifiedQuantity;
        private BigDecimal reworkRejectedQuantity;
    }
}
