package com.kitex.mom.mes.dto;

import com.kitex.mom.common.metrics.MetricFraction;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 当前计划基准的只读到期对象合同；ID、计数和比率通过文本传输。
 */
public final class MesPlanAttainmentDTO {

    private MesPlanAttainmentDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        private String objectLevel = "TASK";
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime startTime;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime endTime;
        private String workshopCode;
        private String taskCode;
        private String routeCode;
        private String groupBy = "WORKSHOP";
        private String closedPolicy = "INCLUDE_UNFINISHED";
        private String category = "ALL";
        private Long current = 1L;
        private String selectedGroupCode;
        private boolean selectedGroupUnattributed;
    }

    public record Scope(String objectLevel, String startTime, String endTime, String workshopCode,
                        String taskCode, String routeCode, String groupBy, String closedPolicy) {
    }

    @Data
    @Accessors(chain = true)
    public static class Summary {
        private String dueCount;
        private String applicableCount;
        private String completedCount;
        private String onTimeCount;
        private String lateCount;
        private String openCount;
        private String closedCount;
        private String draftCount;
        private String unknownCount;
        private MetricFraction.Result completionRate;
        private MetricFraction.Result onTimeRate;
    }

    /**
     * SQL完整分组聚合，仅使用冻结编码；null编码与真实UNKNOWN编码保持独立。
     */
    @Data
    @Accessors(chain = true)
    public static class Aggregate {
        private String groupCode;
        private String dueCount;
        private String onTimeCount;
        private String lateCount;
        private String openCount;
        private String closedCount;
        private String draftCount;
        private String unknownCount;
    }

    @Data
    @Accessors(chain = true)
    public static class Coverage {
        private String missingDueCount;
        private String unattributedDueCount;
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
        private String status;
        private String plannedEndTime;
        private String finishTime;
        private String category;
        private List<String> issues = new ArrayList<>();
    }

    /**
     * 内部SQL映射，issue不直接暴露在响应中。
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
        private String status;
        private LocalDateTime plannedEndTime;
        private LocalDateTime finishTime;
        private String category;
        private String issue;
    }

    @Data
    @Accessors(chain = true)
    public static class Statistics {
        private String contractVersion = "MES_PLAN_ATTAINMENT_V1";
        private String tenantId;
        private String factoryCode;
        private String actorId;
        private String observedAt;
        private String timeZone = "Asia/Shanghai";
        private Scope scope;
        private boolean provisional;
        private String definition;
        private List<String> dataGaps;
        private Summary summary;
        private List<Group> groups;
        private Coverage coverage;
    }

    @Data
    @Accessors(chain = true)
    public static class Details {
        private String contractVersion = "MES_PLAN_ATTAINMENT_V1";
        private String tenantId;
        private String factoryCode;
        private String actorId;
        private String observedAt;
        private String timeZone = "Asia/Shanghai";
        private Scope scope;
        private List<String> dataGaps;
        private String category;
        private String current;
        private String selectedGroupCode;
        private boolean selectedGroupUnattributed;
        private String pageSize = "30";
        private String total;
        private List<Row> rows;
    }
}
