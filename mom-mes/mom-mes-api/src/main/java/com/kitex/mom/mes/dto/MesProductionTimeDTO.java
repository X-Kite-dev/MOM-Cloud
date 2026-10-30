package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 实际生产时间统计合同。秒数用文本传输，防止前端将未知量或大数误转为零。
 */
public final class MesProductionTimeDTO {

    private MesProductionTimeDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime startTime;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime endTime;
        private String taskCode;
        private String produceTaskCode;
        private String arrangeCode;
        private String workshopCode;
        private String shiftCode;
        private String groupBy = "TASK";
        private Integer detailPage = 1;
        private Integer detailSize = 50;

        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException("生产时间统计不支持条件：" + field);
        }
    }

    @Data
    @Accessors(chain = true)
    public static class Fact {
        private String sourceType;
        private String sourceId;
        private String taskCode;
        private String produceTaskCode;
        private String arrangeCode;
        private String workshopCode;
        private String shiftCode;
        private String deviceCode;
        private String workerCode;
        private String identifier;
        private String identifierType;
        /**
         * RUN/LABOR/PAUSE/WORKPIECE_RUN/WORKPIECE_PAUSE。
         */
        private String kind;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private String state;
        private Boolean eligible;
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    public static class Metrics {
        private String processingSeconds;
        private String openProcessingSeconds;
        private String pausedSeconds;
        private String laborSeconds;
        private String workpieceProcessingSeconds;
        private String openWorkpieceProcessingSeconds;
        private String workpiecePausedSeconds;
        private long openIntervalCount;
        private long invalidSourceCount;
        private long unclassifiedPauseCount;
    }

    @Data
    @Accessors(chain = true)
    public static class Group {
        private String key;
        private String label;
        private String taskCode;
        private String produceTaskCode;
        private String arrangeCode;
        private String identifier;
        private String identifierType;
        private Metrics metrics;
    }

    @Data
    @Accessors(chain = true)
    public static class Detail {
        private Fact fact;
        private LocalDateTime windowStart;
        private LocalDateTime windowEnd;
        private String seconds;
        private boolean included;
        private boolean ongoing;
        private String issue;
    }

    @Data
    @Accessors(chain = true)
    public static class Result {
        private String contract = "MES_PRODUCTION_TIME_V1";
        private String factoryCode;
        private Query scope;
        private LocalDateTime observedAt;
        private Metrics totals = new Metrics();
        private List<Group> groups = new ArrayList<>();
        private List<Detail> details = new ArrayList<>();
        private long detailTotal;
        private List<String> dataGaps = new ArrayList<>();
        private List<String> metricDefinitions = new ArrayList<>();
    }
}
