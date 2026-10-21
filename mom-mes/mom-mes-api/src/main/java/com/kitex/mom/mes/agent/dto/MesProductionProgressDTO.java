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
 * MES 生产进度语义查询模型，供智能体和问数场景只读使用。
 */
public final class MesProductionProgressDTO {

    private MesProductionProgressDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "生产进度受控查询")
    public static class Query {
        @NotBlank
        private String factoryCode;
        /**
         * 未指定时间且询问总数、状态或指定工单时，查询当前工厂全部历史工单。
         */
        private Boolean allTime = false;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private String taskCode;
        private String workshopCode;
        @Min(1)
        @Max(50)
        private Integer limit = 20;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "生产进度权威证据")
    public static class Evidence {
        private String toolCode;
        /**
         * ALL_TIME 或 TIME_RANGE，避免模型把全量与时间窗统计混淆。
         */
        private String queryScope;
        private String metricDefinition;
        private String factoryCode;
        private LocalDateTime rangeStart;
        private LocalDateTime rangeEnd;
        private LocalDateTime observedAt;
        private Summary summary;
        private List<TaskProgress> tasks = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        private Boolean truncated;
    }

    @Data
    @Accessors(chain = true)
    public static class Summary {
        /**
         * MATCHED：状态计数针对全部命中工单，不受明细 limit 影响。
         */
        private String taskStatusMetricScope;
        /**
         * RETURNED_DETAILS：数量合计只针对本次返回明细。
         */
        private String quantityMetricScope;
        /**
         * RETURNED_DETAILS：暂停工单依赖工序明细，只针对本次返回明细。
         */
        private String pausedMetricScope;
        private Long matchedTaskCount;
        private Long returnedTaskCount;
        private Long notStartedTaskCount;
        private Long inProgressTaskCount;
        private Long pausedTaskCount;
        private Long finishedTaskCount;
        private Long delayedTaskCount;
        private BigDecimal targetQuantity;
        private BigDecimal completedQuantity;
        private BigDecimal unqualifiedQuantity;
        private BigDecimal completionRate;
    }

    @Data
    @Accessors(chain = true)
    public static class TaskProgress {
        private Long taskId;
        private String taskCode;
        private String productionOrder;
        private String planNo;
        private String materialCode;
        private String materialName;
        private String workshopCode;
        private String workshopName;
        private String status;
        private LocalDateTime plannedStartTime;
        private LocalDateTime plannedEndTime;
        private LocalDateTime actualStartTime;
        private LocalDateTime actualFinishTime;
        private BigDecimal targetQuantity;
        private BigDecimal completedQuantity;
        private BigDecimal unqualifiedQuantity;
        private BigDecimal completionRate;
        private Boolean delayed;
        private Integer operationCount;
        private Integer finishedOperationCount;
        private Integer activeOperationCount;
        private Integer pausedOperationCount;
        private List<OperationProgress> currentOperations = new ArrayList<>();
        private List<String> gaps = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class OperationProgress {
        private String operationTaskCode;
        private String routeCode;
        private String routeName;
        private Integer routeOrder;
        private String status;
        private String worksiteCode;
        private String deviceCode;
        private LocalDateTime plannedStartTime;
        private LocalDateTime plannedEndTime;
        private LocalDateTime actualStartTime;
        private LocalDateTime actualFinishTime;
        private BigDecimal targetQuantity;
        private BigDecimal completedQuantity;
        private BigDecimal unqualifiedQuantity;
    }
}
