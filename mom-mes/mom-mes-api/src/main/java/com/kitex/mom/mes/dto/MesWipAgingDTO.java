package com.kitex.mom.mes.dto;

import com.kitex.mom.common.metrics.MetricFraction;
import com.kitex.mom.common.time.ElapsedDuration;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 当前工序任务队列的只读合同；标识、计数及精确历时均为文本。
 */
public final class MesWipAgingDTO {

    private MesWipAgingDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        private String workshopCode;
        private String taskCode;
        private String routeCode;
        private String groupBy = "WORKSHOP";
        private String thresholdHours = "24";
        private String status = "ALL";
        private String agingCategory = "ALL";
        private Long current = 1L;
        private String selectedGroupCode;
        private boolean selectedGroupUnattributed;
    }

    public record Scope(String workshopCode, String taskCode, String routeCode, String groupBy, String thresholdHours) {
    }

    @Data
    @Accessors(chain = true)
    public static class Summary {
        private String populationCount;
        private String waitingDispatchCount;
        private String waitingReceiveCount;
        private String receivedCount;
        private String producingCount;
        private String pausedCount;
        private String unknownStatusCount;
        private String sourceIssueCount;
        private String knownQueueAgeCount;
        private String unknownQueueAgeCount;
        private String overThresholdCount;
        private MetricFraction.Result overThresholdRate;
        private String oldestNoticeTime;
        private String maxQueueResidenceSeconds;
    }

    /**
     * SQL全量聚合；最早下达仅针对停留证据可核定的子集。
     */
    @Data
    @Accessors(chain = true)
    public static class Aggregate {
        private String groupCode;
        private String populationCount;
        private String waitingDispatchCount;
        private String waitingReceiveCount;
        private String receivedCount;
        private String producingCount;
        private String pausedCount;
        private String unknownStatusCount;
        private String sourceIssueCount;
        private String knownQueueAgeCount;
        private String unknownQueueAgeCount;
        private String overThresholdCount;
        private LocalDateTime oldestNoticeTime;
    }

    @Data
    @Accessors(chain = true)
    public static class Coverage {
        private String unattributedCount;
    }

    public record Group(String groupCode, String groupName, boolean unattributed, Summary summary) {
    }

    @Data
    @Accessors(chain = true)
    public static class Row {
        private String objectId;
        private String objectCode;
        private String taskCode;
        private String workshopCode;
        private String routeCode;
        private String routeNodeCode;
        private String matCode;
        private String status;
        private String noticeTime;
        private String startTime;
        private ElapsedDuration.Result queueResidence;
        private ElapsedDuration.Result sinceFirstStart;
        private String agingCategory;
        private List<String> issues;
    }

    /**
     * 内部SQL证据，不直接序列化到接口。
     */
    @Data
    @Accessors(chain = true)
    public static class Fact {
        private String objectId;
        private String objectCode;
        private String taskCode;
        private String workshopCode;
        private String routeCode;
        private String routeNodeCode;
        private String matCode;
        private String status;
        private LocalDateTime noticeTime;
        private LocalDateTime startTime;
        private LocalDateTime finishTime;
        private String sourceIssue;
        private String queueIssue;
        private String startIssue;
        private String agingCategory;
    }

    @Data
    @Accessors(chain = true)
    public static class Statistics {
        private String contractVersion = "MES_WIP_AGING_V1";
        private String tenantId;
        private String factoryCode;
        private String actorId;
        private String observedAt;
        private String timeZone = "Asia/Shanghai";
        private Scope scope;
        private List<String> dataGaps;
        private String definition;
        private Summary summary;
        private List<Group> groups;
        private Coverage coverage;
    }

    @Data
    @Accessors(chain = true)
    public static class Details {
        private String contractVersion = "MES_WIP_AGING_V1";
        private String tenantId;
        private String factoryCode;
        private String actorId;
        private String observedAt;
        private String timeZone = "Asia/Shanghai";
        private Scope scope;
        private List<String> dataGaps;
        private String status;
        private String agingCategory;
        private String current;
        private String selectedGroupCode;
        private boolean selectedGroupUnattributed;
        private String pageSize = "30";
        private String total;
        private List<Row> rows;
    }
}
