package com.kitex.mom.mes.agent.dto;

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
 * MES 工序在制与停滞风险模型，供智能体只读问数使用。
 */
public final class MesOperationWipDTO {

    private MesOperationWipDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "MES 工序在制与停滞风险受控查询")
    public static class Query {
        @NotBlank
        private String factoryCode;
        private String taskCode;
        private String workshopCode;
        @Min(1)
        @Max(50)
        private Integer limit = 20;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "MES 工序在制与停滞风险权威证据")
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String factoryCode;
        private LocalDateTime observedAt;
        private Summary summary;
        private List<StatusSummary> statusSummaries = new ArrayList<>();
        private List<OperationAttention> operations = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        private Boolean truncated;
    }

    @Data
    @Accessors(chain = true)
    public static class Summary {
        private Long openOperationCount;
        private Long producingOperationCount;
        private Long pausedOperationCount;
        private Long overdueOperationCount;
        private Long waitingStartOperationCount;
        private Long attentionOperationCount;
    }

    @Data
    @Accessors(chain = true)
    public static class StatusSummary {
        private String status;
        private String statusName;
        private Long operationCount;
    }

    @Data
    @Accessors(chain = true)
    public static class OperationAttention {
        private Long operationId;
        private String operationTaskCode;
        private String taskCode;
        private String materialCode;
        private String materialName;
        private String routeCode;
        private String routeName;
        private Integer routeOrder;
        private String status;
        private String statusName;
        private String workshopCode;
        private String workshopName;
        private String worksiteCode;
        private String worksiteName;
        private String deviceCode;
        private String deviceName;
        private LocalDateTime plannedStartTime;
        private LocalDateTime plannedEndTime;
        private LocalDateTime actualStartTime;
        private BigDecimal targetQuantity;
        private BigDecimal completedQuantity;
        private BigDecimal unqualifiedQuantity;
        private BigDecimal completionRate;
        private Boolean overdue;
        private Long overdueMinutes;
        private Boolean waitingStart;
        private Long waitingMinutes;
        private String attentionLevel;
        private List<String> riskReasons = new ArrayList<>();
    }
}
