package com.kitex.mom.qms.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 指定物料批次的正式召回治理状态。
 *
 * <p>该对象只投影QMS既有召回单和实名履历，不创建召回、不修改库存，也不把模型结论
 * 当成质量放行或召回关闭回执。</p>
 */
public final class QmsBatchRecallStatusDTO {

    private QmsBatchRecallStatusDTO() {
    }

    /** 精确批次范围。 */
    @Data
    @Accessors(chain = true)
    public static class Query {
        @NotBlank
        private String factoryCode;
        @NotBlank
        @Size(max = 200)
        private String materialCode;
        @NotBlank
        @Size(max = 200)
        private String batchNo;
        @Min(1)
        @Max(20)
        private Integer detailLimit = 10;
    }

    /** 当前批次的召回治理只读投影。 */
    @Data
    @Accessors(chain = true)
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String factoryCode;
        private String materialCode;
        private String batchNo;
        private LocalDateTime observedAt;
        private Boolean recallRecordFound;
        private Long openRecallCount;
        private Long closedRecallCount;
        private RecallRecord latestRecall;
        private Integer returnedRecordCount;
        private List<RecallRecord> records = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        private Boolean truncated;
    }

    /** 一张召回治理单的审计摘要；证据正文和动作明细仍在QMS召回工作台查看。 */
    @Data
    @Accessors(chain = true)
    public static class RecallRecord {
        private Long recallId;
        private String recallNo;
        private String severity;
        private String status;
        private String reason;
        private String evidenceHash;
        private String downstreamScopeHash;
        private LocalDateTime observedAt;
        private Long mesMatchedReportCount;
        private Long wmsPositiveStockRowCount;
        private Long qmsOpenNcrCount;
        private Boolean customerNotificationRequired;
        private String proposedByName;
        private LocalDateTime proposedTime;
        private String reviewedByName;
        private LocalDateTime reviewedTime;
        private String executedByName;
        private LocalDateTime executedTime;
        private String verifiedByName;
        private LocalDateTime verifiedTime;
        private String verificationConclusion;
        private LocalDateTime closedTime;
        private String lastError;
    }
}
