package com.kitex.mom.qms.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CAPA/8D 报告使用的全周期权威证据合同；报告审核不改变这些业务事实。
 */
public final class QmsCapaEvidenceDTO {
    private QmsCapaEvidenceDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "CAPA全周期只读取证请求，组织范围由实名身份冻结")
    public static class Query {
        @NotNull
        @Positive
        private Long capaId;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "CAPA及来源NCR全周期权威证据，不将缺阶段报告视为业务关闭")
    public static class Evidence {
        private String toolCode;
        private Integer contractVersion;
        private String tenantId;
        private String factoryCode;
        private String capaId;
        private String capaNo;
        private Integer currentCycle;
        private String status;
        private LocalDateTime observedAt;
        private String sourceFingerprint;
        private List<String> evidenceRefs = new ArrayList<>();
        private List<String> missingEvidence = new ArrayList<>();
        private Map<String, Boolean> stageCompletion = new LinkedHashMap<>();
        private Map<String, Object> capa = new LinkedHashMap<>();
        private Map<String, Object> sourceNcr = new LinkedHashMap<>();
        private List<Map<String, Object>> causes = new ArrayList<>();
        private List<Map<String, Object>> actions = new ArrayList<>();
        private List<Map<String, Object>> operations = new ArrayList<>();
        private List<Map<String, Object>> reopenReceipts = new ArrayList<>();
        private Boolean truncated = false;
    }
}
