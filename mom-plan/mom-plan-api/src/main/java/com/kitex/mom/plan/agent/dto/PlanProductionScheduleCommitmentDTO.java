package com.kitex.mom.plan.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MES生产工单到PLAN正式排程承诺的只读Agent证据契约。
 *
 * <p>该契约按MES保存的APS拆合批来源映射定位PLAN计划，禁止按产品、时间或单号相似度
 * 猜测归属。正式承诺仅来自计划当前锁定任务中的工序占用。</p>
 */
public final class PlanProductionScheduleCommitmentDTO {

    private PlanProductionScheduleCommitmentDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "生产工单正式排程承诺查询")
    public static class Query {
        private String factoryCode;
        private String taskCode;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "生产工单对应的PLAN正式排程承诺证据")
    public static class Evidence {
        private String toolCode;
        private String factoryCode;
        private String taskCode;
        private String mesMappingStatus;
        private Boolean mesMappingComplete;
        private String mesMappingMessage;
        private String mesMappingFingerprint;
        private LocalDateTime observedAt;
        private Integer sourcePlanCount;
        private Integer committedPlanCount;
        private List<PlanCommitment> plans = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        private Boolean truncated;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "单个PLAN来源计划的正式排程承诺")
    public static class PlanCommitment {
        private Long issueId;
        private String issueNo;
        private String productCode;
        private String productName;
        private BigDecimal issueQuantity;
        private BigDecimal mesAllocatedQuantity;
        private BigDecimal mesCompletedQuantity;
        private LocalDate demandDate;
        private String issueStatus;
        private Boolean commitmentAvailable;
        private Boolean lockStatus;
        private Long lockTaskId;
        private String apsTaskNo;
        private Long scenarioId;
        private String scenarioNo;
        private String scenarioStatus;
        private String candidateCode;
        private String candidateName;
        private LocalDateTime scheduledStartTime;
        private LocalDateTime scheduledEndTime;
        private Integer operationCount;
        private Integer hardBlockedOperationCount;
        private String commitmentFingerprint;
        private List<OperationCommitment> operations = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private Boolean operationTruncated;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "正式排程中的工序级时间与资源承诺")
    public static class OperationCommitment {
        private Long operationId;
        private Integer operationSeq;
        private String processCode;
        private String processName;
        private String resourceType;
        private String resourceCode;
        private String resourceName;
        private String deviceCode;
        private String deviceName;
        private String employeeCode;
        private String employeeName;
        private String toolingCodes;
        private LocalDateTime scheduledStartTime;
        private LocalDateTime scheduledEndTime;
        private String materialConstraintStatus;
        private Boolean hardBlocked;
        private String constraintSummary;
        private Boolean lockStatus;
    }
}
