package com.kitex.mom.qms.agent.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 指定生产工单的过程检验和成品检验权威证据。
 */
public final class QmsWorkOrderQualityDTO {

    private QmsWorkOrderQualityDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "QMS生产工单质量受控查询")
    public static class Query {
        @NotBlank
        private String factoryCode;
        @NotBlank
        private String workOrderCode;
        @Min(1)
        @Max(50)
        private Integer limit = 20;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "QMS生产工单质量权威证据")
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String factoryCode;
        private String workOrderCode;
        private LocalDateTime observedAt;
        private Summary summary;
        private List<QualityTask> tasks = new ArrayList<>();
        private List<Nonconformance> nonconformances = new ArrayList<>();
        private List<Capa> capas = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        private Boolean truncated;
    }

    @Data
    @Accessors(chain = true)
    public static class Summary {
        private Long taskCount;
        private Long waitingTaskCount;
        private Long runningTaskCount;
        private Long unhandledTaskCount;
        private Long finishedTaskCount;
        private Long qualifiedTaskCount;
        private Long unqualifiedTaskCount;
        private Long exemptionTaskCount;
        private Long emergencyReleaseTaskCount;
        private Long concessionReceiveTaskCount;
        private Long canceledTaskCount;
        /** 已判定合格与不合格任务中的合格百分比，无样本时为空。 */
        private BigDecimal strictTaskQualificationRate;
    }

    @Data
    @Accessors(chain = true)
    public static class QualityTask {
        private String inspectionType;
        @JsonSerialize(using = ToStringSerializer.class)
        private Long taskId;
        private String sourceTaskCode;
        private String workOrderCode;
        private String materialCode;
        private String materialName;
        private String batchNo;
        private String processCode;
        private String processName;
        private String status;
        private String statusName;
        private String result;
        private String resultName;
        private Integer inspectionQuantity;
        private Integer usableQuantity;
        private Integer handledQuantity;
        private LocalDateTime lastInspectionTime;
        private LocalDateTime createTime;
    }

    /** 仅由同工单过程/成品检验任务主键反查的正式 NCR。 */
    @Data
    @Accessors(chain = true)
    public static class Nonconformance {
        private String ncrId;
        private String ncrNo;
        private String sourceType;
        private String sourceTaskId;
        private String status;
        private String materialCode;
        private String batchNo;
        private String capaId;
        private String capaStatus;
    }

    /** 经 NCR 当前关联主键及来源关系双重核对的 CAPA。 */
    @Data
    @Accessors(chain = true)
    public static class Capa {
        private String capaId;
        private String capaNo;
        private String sourceNcrId;
        private String status;
        private String effectivenessResult;
        private LocalDateTime closedTime;
    }
}
