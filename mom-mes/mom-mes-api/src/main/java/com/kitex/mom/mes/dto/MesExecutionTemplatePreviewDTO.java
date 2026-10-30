package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 只返回适用范围/选择证据，不返回可执行工程/标识冻结包。
 */
@Data
public class MesExecutionTemplatePreviewDTO {
    private String version = "MES_EXECUTION_TEMPLATE_PREVIEW_V1";
    private String tenantId;
    private String factoryCode;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime evaluatedAt;
    private MesExecutionTemplatePreviewQueryDTO scenario;
    private int total;
    private int matchedCount;
    private Candidate selected;
    private boolean rankingTie;
    private String message;
    private List<Candidate> records;

    @Data
    public static class Candidate {
        private String templateId;
        private String templateCode;
        private String templateName;
        private Integer versionNo;
        private String status;
        private int priority;
        private int specificity;
        private String reportMode;
        private String materialCode;
        private String routeCode;
        private String processRouteCode;
        private String routeNodeCode;
        private String workshopCode;
        private String worksiteCode;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime effectiveFrom;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime effectiveTo;
        private boolean matched;
        private boolean selected;
        private List<String> reasons;
    }
}
