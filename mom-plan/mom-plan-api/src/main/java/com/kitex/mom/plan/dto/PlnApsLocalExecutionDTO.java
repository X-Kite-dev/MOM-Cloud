package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/** 原发布回执、当前计划与真实执行分开呈现，不创建同步、补偿或回评记录。 */
public final class PlnApsLocalExecutionDTO {
    private PlnApsLocalExecutionDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Page {
        @Schema(description = "统一案件及原领域候选主键，字符串")
        private String caseId;
        private String candidateId;
        @Schema(description = "PLAN原业务当前状态及原发布号")
        private String candidateStatus;
        private String publicationRequestId;
        @Schema(description = "对应原情景，可在现有效果回评入口查询")
        private String effectScenarioId;
        @Schema(description = "逐计划分页，每页最多5个计划；当前页统计不能冒充全部计划统计")
        private int current;
        private int pageSize;
        private int total;
        private List<PlanEvidence> records;
    }

    @Data
    @Accessors(chain = true)
    public static class PlanEvidence {
        @Schema(description = "原生产计划主键和编号")
        private String issueId;
        private String issueNo;
        @Schema(description = "原应用账状态，与当前计划和实际执行状态相互独立")
        private String receiptState;
        private List<ReceiptEvidence> receipts;
        @Schema(description = "当前MES读取是否成功；读取失败时不把工单存在性和进度填为零")
        private Boolean mesAvailable;
        private Boolean mesTaskPresent;
        @Schema(description = "NOT_PUBLISHED/ROLLED_BACK/MATCHED/DRIFTED/UNKNOWN；仅比较本次候选时刻")
        private String scheduleState;
        @Schema(description = "NO_TASK/NOT_STARTED/EXECUTING/FINISHED/DATA_GAP，不等于原候选效果验收")
        private String progressState;
        private Integer plannedOperationCount;
        private Integer matchedOperationCount;
        private Integer startedOperationCount;
        private Integer finishedOperationCount;
        @Schema(description = "当前页内该计划的全部节点执行证据，界面再分页")
        private List<OperationEvidence> operations;
        @Schema(description = "只读取证时的缺口，不能自动转成重发或执行失败")
        private List<String> gaps;
    }

    /** PLAN只读投影，避免PLAN API与MES API互相依赖。 */
    @Data
    @Accessors(chain = true)
    public static class ReceiptEvidence {
        private String requestId;
        private String appliedBy;
        private String appliedTime;
        private Integer updatedOperationCount;
        private Boolean fingerprintMatched;
    }

    @Data
    @Accessors(chain = true)
    public static class OperationEvidence {
        @Schema(description = "冻结路线节点，旧协议仅在原工序唯一时桥接")
        private String routeNodeCode;
        private String processCode;
        private String processName;
        @Schema(description = "候选时刻与MES当前计划时刻，不覆盖历史候选")
        private String expectedStartTime;
        private String expectedEndTime;
        private String currentStartTime;
        private String currentEndTime;
        private Boolean scheduleMatched;
        @Schema(description = "实际工序任务编码、状态及真实开完工，未知保留空")
        private String produceTaskCode;
        private String mesStatus;
        private String actualStartTime;
        private String actualFinishTime;
        private String completeQuantity;
        private Boolean movable;
        private String blockedReason;
        @Schema(description = "相对候选时刻的真实开完工偏差分钟，未知为空")
        private Long startDeviationMinutes;
        private Long finishDeviationMinutes;
        private String gap;
    }
}
