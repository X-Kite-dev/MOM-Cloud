package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 受控替代派工合同；所有业务Long身份在HTTP边界用精确字符串。
 */
public final class MesDispatchCorrectionDTO {

    public static final String VERSION = "MES_DISPATCH_CORRECTION_V1";

    private MesDispatchCorrectionDTO() {
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class Command {

        private String requestId;

        private String action;

        private String arrangeId;

        private String correctionId;

        private String expectedFingerprint;

        private String shiftCode;

        private List<String> worksiteCodes;

        private String reason;

        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException("修正命令包含未授权字段：" + field);
        }
    }

    @Data
    public static class Choice {

        private String code;

        private String name;

        public Choice() {
        }

        public Choice(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    @Data
    public static class Preview {

        private String contractVersion = VERSION;

        private String tenantId;

        private String factoryCode;

        private LocalDateTime observedAt;

        private String evidenceFingerprint;

        private boolean eligible;

        private List<String> blockers;

        private MesTaskArrangeEntity arrange;

        private String targetShiftCode;

        private String targetShiftName;

        private List<String> targetWorksiteCodes;

        private List<Choice> candidateShifts;

        private List<Choice> candidateWorksites;
    }

    @Data
    public static class Correction {

        private String id;

        private String arrangeId;

        private String arrangeCode;

        private String taskCode;

        private String produceTaskCode;

        private String status;

        private String sourceFingerprint;

        private String evidenceFingerprint;

        private String targetShiftCode;

        private String targetShiftName;

        private List<String> targetWorksiteCodes;

        private String reason;

        private String applicantId;

        private String applicantCode;

        private String applicantName;

        private LocalDateTime submittedTime;

        private String reviewerId;

        private String reviewerCode;

        private String reviewerName;

        private LocalDateTime reviewedTime;

        private String reviewReason;

        private String replacementArrangeId;

        private String replacementArrangeCode;

        private String beforeSnapshot;

        private String afterSnapshot;
    }

    @Data
    public static class Operation {

        private String requestId;

        private String action;

        private String actorId;

        private String actorCode;

        private String reason;

        private LocalDateTime createdTime;
    }

    @Data
    public static class Detail {

        private String contractVersion = VERSION;

        private String tenantId;

        private String factoryCode;

        private LocalDateTime observedAt;

        private Correction correction;

        private List<Operation> operations;

        private Preview currentPreview;
    }

    @Data
    public static class Resolution {

        private String contractVersion = VERSION;

        private String tenantId;

        private String factoryCode;

        private String requestId;

        private String state;

        private Detail result;
    }

    @Data
    public static class Page {

        private String contractVersion = VERSION;

        private String tenantId;

        private String factoryCode;

        private LocalDateTime observedAt;

        private List<Correction> records;

        private long total;

        private long current;

        private long size;
    }
}
