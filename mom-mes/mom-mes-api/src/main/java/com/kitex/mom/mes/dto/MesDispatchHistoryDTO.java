package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 历史查询只读合同；说明追加审计，不能覆盖原实绩。
 */
public final class MesDispatchHistoryDTO {
    private MesDispatchHistoryDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        private String taskCode;
        private String produceTaskCode;
        private String arrangeCode;
        private String arrangeId;
        private String workshopCode;
        private String worksiteCode;
        private String workerCode;
        private String shiftCode;
        private String status;
        private String executionTemplateCode;
        private Integer executionTemplateVersion;
        private String dateBasis;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime startTime;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime endTime;
        private Integer current;
        private Integer size;
    }

    @Data
    @Accessors(chain = true)
    public static class NoteCommand {
        private String requestId;
        private String arrangeId;
        private String expectedSourceFingerprint;
        private String note;
    }

    @Data
    @Accessors(chain = true)
    public static class PrintCommand {
        private String arrangeId;
        private String expectedSourceFingerprint;
        private String templateId;
        private String expectedTemplateFingerprint;
    }

    @Data
    @Accessors(chain = true)
    public static class Detail {
        private String contractVersion = "MES_DISPATCH_HISTORY_V1";
        private String tenantId;
        private String factoryCode;
        private LocalDateTime observedAt;
        private MesTaskArrangeEntity arrange;
        private MesTaskEntity task;
        private MesProduceTaskEntity produceTask;
        private Map<String, Object> engineering;
        private List<Map<String, String>> sopDocuments;
        private List<String> dataGaps;
        private String sourceFingerprint;
    }
}
